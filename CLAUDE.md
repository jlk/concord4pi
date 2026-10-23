# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

concord4pi bridges a Concord alarm panel's SuperBus 2000 (SB2000) Automation Module, connected over RS232 serial, to an MQTT broker. It publishes alarm state to MQTT topics and can optionally relay raw panel commands received over MQTT back to the panel. Plain Java, no framework. There's a JUnit 5 unit test suite in `test/` (see "Tests" below).

## Build and run

- Build: `ant` (default target `dist`). This compiles `src/concord4pi` against every jar in `lib/` into `build/`, then writes **`jars/concord4pi.jar`, a file tracked in git**. Rebuilding changes a committed artifact, so check whether the user wants the jar committed.
  - **Don't commit the jar that ant builds.** The committed `jars/concord4pi.jar` (last changed in `e1659f0`) is a ~1.3 MB jar that ant can't reproduce. It bundles Paho, tinylog and jSerialComm, has `Main-Class: concord4pi.concord4pi`, and is Java 17 bytecode. `startup/start.sh` depends on that, because it runs the jar with no other classpath. Ant's jar (~57 KB) has only the project classes and no `Main-Class`. After running `ant`, restore it with `git checkout -- jars/concord4pi.jar`. Docker is unaffected, because it rebuilds with ant and uses the full classpath.
- Clean: `ant clean`
- Language level: Java 21. The Docker image is `eclipse-temurin:21-jdk-alpine`, and it runs on an amd64 server. `build.xml` compiles with `release="${java.release}"` (21), so a jar built on a newer local JDK still runs in the image. If the base image changes, change `java.release` with it. The `openjdk` Docker images are deprecated, and the old `openjdk:15-jdk-alpine` tag no longer exists.
- For a quick compile without ant: `javac -d <outdir> -cp <lib jars> $(find src -name '*.java')`.
- Run from the repo root. `Config` reads the relative path `config/concord4pi.config`, and the app exits if `SerialDevice` is unset or the port can't be opened:
  ```
  java -Dtinylog.configuration=config/log.config -cp lib/jSerialComm-2.9.3.jar:jars/concord4pi.jar:lib/org.eclipse.paho.client.mqttv3-1.1.1.jar:lib/tinylog-api-2.6.0.jar:lib/tinylog-impl-2.6.0.jar concord4pi.concord4pi
  ```
  The ant build does not bundle the `lib/` jars into the output jar, so the full classpath is required. `startup/entrypoint.sh` uses this form. The classpath in the README, and `startup/start.sh` (jar only), are out of date.
- Docker: `docker build -t concord4pi .` builds with ant inside `eclipse-temurin:21-jdk-alpine` (amd64 and arm64 only), and uses `sed` to switch `config/log.config` from file to console logging. The JSON-form `ENTRYPOINT` and the `exec java` in `startup/entrypoint.sh` make the JVM PID 1, so `docker stop` delivers SIGTERM and `MainController`'s shutdown hook runs. Keep both.
- Without hardware: `debug/setupDebugSerial` uses `socat` to create a linked pseudo-serial pair (`debug/SERIALIN` / `debug/SERIALOUT`) with the SB2000 line settings (9600 8O1). Point `SerialDevice` at one end and write to the other.
- When upgrading a dependency in `lib/`, update the versioned jar names in `startup/entrypoint.sh` (and in the README).

## Tests

- Run: `ant test`. This compiles `test/` into `build-test/` and runs every test through the JUnit console launcher. A failing test fails the build. `ant` (dist) never runs or bundles tests.
- Layout: `test/` mirrors the package structure. JUnit 5.13.4 lives in `lib/test/` (kept on the 5.x line because JUnit 6 needs Java 17). The production `compile` only sees `lib/*.jar`. `test/tinylog.properties` keeps test logging at `error`.
- Helpers in `test/concord4pi/testsupport/`:
  - `RecordingBroadcaster`: captures every published topic and value. `RecordingBroadcaster.newAlarmSystem(recorder)` gives a wired-up `AlarmSystemData`.
  - `Messages`: builds valid frames with the length and checksum computed (`build`, `frame`, `frameWithBadChecksum`, `wire`).
  - `FakeSerialController`: records writes as `"ACK"`, `"NAK"` or the message text, without `MESSAGE_START`.
- **Known-bug convention:** each defect under "Known defects" has a test asserting the *correct* behavior, marked `@Disabled("Known bug: ...")`, so the suite stays green. When you fix a bug, delete its `@Disabled` line and the test becomes the check. When you find a new bug, add a disabled test the same way and list it under "Known defects". To confirm the disabled tests still fail, run the launcher with `--config=junit.jupiter.conditions.deactivate=org.junit.*DisabledCondition`.
- `SB2000ControllerTest` must be in package `concord4pi.SB2000`. It uses the package-private seams (see "Testability notes") and calls `processIncomingMessages()` / `processOutgoingMessages()` directly to step the run loop, without threads.
- `MessageProcessorTest` fixtures are built from the handlers' field offsets. `RealFramesTest` replays verbatim frames captured from the user's panel, which confirms those offsets on real data. Only non-identifying zone names are included, and no `09` frames. This panel reports its partition ID as `0100` (partition 1, area 0), not `0001`.
- Not covered: `MQTTService`, `MainController`, `concord4pi`, real serial I/O, and thread timing.

## Architecture

The work is split across three threads that communicate only through `ConcurrentLinkedQueue`s and poll in 50ms sleep loops:

1. **`SB2000Controller`** (`src/concord4pi/SB2000/`): owns the serial port through `SerialController`. A jSerialComm data listener appends raw bytes to a string buffer, guarded by a semaphore. The run loop frames messages: `MESSAGE_START`, then a 2-hex-char length, then the payload and checksum, all ASCII hex. It ACKs or NAKs each frame and pushes valid `IOMessage`s onto `rxQueue`. Outgoing `IOMessage`s go to `txVerifyQueue` until the panel ACKs them, and are retransmitted on NAK up to `maxReTransmit`. A `ControlMessage` is a single ACK/NAK byte.
2. **`MainController`** (main thread): drains `rxQueue` into `MessageProcessor`, collects inbound commands from every `IBroadcaster`, and queues any replies or commands back to `SB2000Controller`. At startup it sends `FULLCOMMAND_FullEquipmentList` so the panel reports all zones and partitions.
3. **Broadcasters** (`src/concord4pi/messaging/`, implementing `IBroadcaster`): `MQTTService` is currently the only one, and it is enabled by `MQTTBroadcaster=true`. Each broadcaster runs in its own thread and publishes queued `NotificationPacket`s. If `MQTTEnableCommands=true`, messages arriving on `<MQTTBaseTopic>/<MQTTCommandTopic>` are parsed as raw SB2000 hex commands, and a receipt is published under `<base>/<cmd>/receipt/{text,code,message}`. To add an output channel (the README mentions a REST API), implement `IBroadcaster` and register it in `MainController.addBroadcasters()`.

**Message dispatch:** `MessageProcessor.processMessage` switches on `IOMessage.getCommandCodeString()` against the `COMMANDCODE_*` constants in `SB2000Constants`. That class holds all protocol constants: command codes, zone, siren and trouble codes, and their text names. Codes starting with `0x22` or `0x23` are two-byte commands (see `IOMessage.MULTIBYTECOMMAND_PREFIX`). Each handler slices fields out of `getDataString()` by character offset (2 hex chars per byte). Text fields are sequences of 2-char tokens that are decoded through `SB2000Constants.convertTextToken`.

**`IOMessage` format:** the constructor takes **ASCII hex bytes**, not binary. For example, `new IOMessage("020204".getBytes())` gives length `02`, command `02`, no data, and checksum `04`. The ASCII hex string excludes the leading `MESSAGE_START` (`0x0A`) and includes the trailing checksum. The length byte counts itself, the command, and the data, but not the checksum. The checksum is the low byte of the sum of the length, command, and data bytes. `test/concord4pi/testsupport/Messages` builds valid frames from a command and data. `isValid()` checks both the declared length and the checksum. A malformed input (odd length, non-hex, wrong length) doesn't throw. It produces an invalid message with an empty command code. `getMessageBytes()` is an unimplemented stub that returns `null`.

**State model and MQTT topics** (`src/concord4pi/data/`): `AlarmSystemData` is the root. It has an `AlarmPanel` and creates `AlarmAreaPartition`s lazily (`getAreaPartitionByID`) as the panel reports them. Partitions in turn own zones, touchpads, users, sirens, arming level, troubles, and so on. Every node implements `IAlarmObject`:
- `myAddress()` returns `parent.myAddress() + "/<segment>/" + ID`. This path becomes the MQTT topic suffix. IDs are the raw 4-hex-char strings from the panel, e.g. `/partitionarea/0001/zones/0005/status`. `MQTTService` publishes to `MQTTBaseTopic + suffix` with no separator added, because the suffix already starts with `/`.
- Setters call `updateTrigger(new NotificationPacket(myAddress() + "/field", value, action))`. The packet bubbles up through the parents to `AlarmSystemData.updateTrigger`, which hands it to every broadcaster. `MQTTService` skips `NPACTION_NEW` packets that have empty values. Every constructor fires one of these empty `NEW` packets.
- Lookups like `getZoneByID` and `getUser` **create** the child if it's missing, so a lookup on a mistyped ID silently adds a new node.
- The model has no hardware or network dependency. Construct `AlarmSystemData` with a stub `IBroadcaster` that records the packets, and you can drive it directly or through `MessageProcessor`. This is the easiest place to observe behavior.
- Naming convention: topics and log text say "partitionarea" (issue #7), even though the class is still named `AlarmAreaPartition`.

## Testability notes

- `LogEngine` ignores its `Config` argument, so `new LogEngine(null)` works. If tinylog has no configuration, it logs everything to the console at trace level. Pass `-Dtinylog.writer.level=error` (or supply a config) to quiet it.
- `SerialController`'s public constructor opens the real port and calls `System.exit(0)` on failure. `MQTTService`'s constructor connects to the broker and also exits if it can't create the client.
- Seams added for tests. Keep them, and keep them free of behavior changes:
  - `SerialController(LogEngine)` (protected): doesn't open a port.
  - `SB2000Controller(SerialController, LogEngine)` (package-private): the public constructor delegates to it.
  - `SB2000Controller.appendReceivedData(byte[])` (package-private): the serial listener calls it.
  - `processIncomingMessages()` and `processOutgoingMessages()` (package-private).
  - `Config(String configFileName)`: the no-argument constructor uses `config/concord4pi.config`.
- `Config`'s `Properties` field is `static`, so each new `Config` replaces the values that every instance sees.
- Several failure paths call `System.exit(0)`, so the process reports success even when startup fails.

## Known defects (verified 2026-10-23; remove entries as they're fixed)

Don't write tests or code that rely on these behaviors as if they were correct.
- **History lists are backwards.** Zone text, partition text, key fob commands, trouble events, and siren sync events append to the end of the list, and then trim by removing the *last* entry. Getters read index 0. As a result `getText()`, `getKeyFobCommandText()`, `getMostRecent*()` and similar return the *oldest* entry, and entries after the fifth are dropped. `AlarmZone.setKeyFobCommand` therefore publishes the wrong `/keyFob/command/text`. `AlarmZone.getPastText()` and `getPastKeyFobCommand()` throw on an empty list.
- **`AlarmTemperature.myAddress()`** uses `parent.getID()` instead of `parent.myAddress()`, which produces the topic `0001/temperature` (no leading `/` and no `partitionarea`).
- **`AlarmAreaPartition.getFeatureSetText()`** uses an `else if` chain, so it reports only the first set feature.
- **`AlarmArmingLevel` delay flags are decoded from the wrong bits.** Real panel traffic shows exit delay is bit 6 (`0x40`, sent right after arming) and end-of-delay is bit 7 (`0x80`, e.g. `0xC0` when an exit delay finishes). By the protocol's bit numbering, the delay length is bits 4–5 (`0x10` extended, `0x20` twice extended). The code's masks are one bit low (`0x20`, `0x40`, `0x18`). The standard/extended check also masks with 0, so it always reports `STANDARD`. `setDelayFlags` publishes the number and the text to the same `/delayFlags` topic.
- **`AlarmArmingLevel.isArmed()`** is inverted: it returns true for level ≤ 1 (Zone Test or Off). It has no callers yet.
- **`AlarmAreaPartition.isArea()` / `isPartition()`** parse the hex ID halves with radix 10, so an ID containing A–F (for example `000A`) throws a `NumberFormatException`. `isArea()` runs every time arming-level text is built.
- **Radix:** `MessageProcessor` parses arming level (in `partitionData` and `armingLevel`) and feature state with radix 10. Every other field uses radix 16. Feature state values of `0x0A` and above decode wrong.
- **Unhandled command codes.** The panel sends these, but `MessageProcessor` has no `case` for them, so each one logs `Received Message ... without handler` at WARN and is otherwise ignored. They're mostly answers to the startup equipment-list request. In a real capture (2026-10-23), the frames were `05` ×6, `06` ×9, `07` ×6, `08` ×1, `09` ×3, `0A` ×16, `0C` ×6 and `2301` ×12. Per the SB2000 automation protocol (names from memory, so check against the protocol document before implementing), they are:
  - `05` SuperBus device data (constant `COMMANDCODE_SELSUPERBUSDEVICE` exists)
  - `06` SuperBus device capabilities
  - `07` output data
  - `08` equipment list complete
  - `09` user data
  - `0A` schedule data
  - `0C` light-to-sensor attachment
  - `2301` lights state (constant `COMMANDCODE_LIGHTSSTATE` exists)

  `RealFramesTest.UNHANDLED` holds one real example of each code except `09`.
- **`09` user data appears to contain user codes (PINs) in plain BCD**, including the system master and installer codes. Never commit `09` frames, and never publish those bytes to MQTT if a handler is ever added. Treat debug logs as sensitive, because `SB2000Controller` logs every raw frame at DEBUG.
- **Text tokens:** word tokens (such as `6E` FRONT and `57` DOOR) are concatenated with no space, which gives `FRONTDOOR`. The panel separates letter tokens with an explicit space token (`2B`), but not word tokens. Tokens `FB` and `FE` have no mapping and decode to `""`. In touchpad text, `FB` appears to separate the two display lines, so `*FRI OCT 23` and `03:40 PM P1` run together as `...2303:40...`.
- **`MessageProcessor.partitionData`** (command `04`) decodes the partition's text tokens and then discards them. The text is never stored or published, which looks unfinished.
- **`MessageProcessor.timeAndDate`** passes the panel's 1-based month to `Calendar` (which is 0-based) and the 2-digit year as is.
- **`SB2000Controller` framing:** if leading bytes aren't `MESSAGE_START`, ACK, or NAK, the whole buffer is requeued, and processing waits until more serial data arrives. The leading garbage is then framed as a message and NAKed. Every pending `IOMessage` is written immediately, without waiting for each ACK. ACKs and NAKs are matched to `txVerifyQueue` by order alone.
- **`SerialController.setupSerialPort()`**: a malformed `SerialDevice` value (such as the placeholder in the committed config) makes `SerialPort.getCommPort` throw an uncaught `SerialPortInvalidPortException`, so the friendly "could not be opened" message is never shown.
- **Shutdown runs twice:** on SIGTERM, the `appTerminator` shutdown hook calls `MainController.shutdown()` and sets `isRunning = false`. The main loop then exits, and `concord4pi.go()` calls `shutdown()` again, possibly at the same time as the hook. With `docker stop`, the log shows "Serial Port ... CLOSED!" twice. It's harmless so far, because the second close succeeds.
- **`MainController.addBroadcasters()`** calls `.equals` on `MQTTBroadcaster` and `MQTTEnableCommands` without a null check. A missing key causes an NPE.

## Performance notes (current branch is `perf-improvements`)

- `LogEngine.log` calls `Thread.currentThread().getStackTrace()` **twice on every call**, before tinylog filters by level. Callers also build their message strings eagerly. Both costs are paid for TRACE and DEBUG messages even when those levels are off, and the serial and processing loops log heavily at those levels.
- Every thread polls with `Thread.sleep(50)`. `MQTTService.shutdown()` and `SerialController.closePort()` busy-spin on `while(isConnected())` and `while(isOpen())`.
- The serial receive buffer is a `String` that's concatenated on every serial event and re-sliced with `substring` for each frame.
- The README says MQTT client persistence is disabled, but `MQTTService` still uses `MqttDefaultFilePersistence("/tmp/concord4pi")`.

## Configuration

- `config/concord4pi.config` is a Java properties file read by `Config`. The README lists the keys. `Config.getProperty` can return `null`, and `MainController.addBroadcasters()` calls `.equals` directly on the `MQTT*` values, so a missing key can cause an NPE.
- `config/log.config` configures tinylog. `writer.level` is one of error/warn/info/debug/trace, and the system property `-Dtinylog.configuration` points to this file. Log calls go through `LogEngine.log(msg, Level)`.

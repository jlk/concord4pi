# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

concord4pi bridges a Concord alarm panel's SuperBus 2000 (SB2000) Automation Module, connected over RS232 serial, to an MQTT broker. It publishes alarm state to MQTT topics and can optionally relay raw panel commands received over MQTT back to the panel. Plain Java, no framework, and no test suite.

## Build and run

- Build: `ant` (default target `dist`). This compiles `src/concord4pi` against every jar in `lib/` into `build/`, then writes **`jars/concord4pi.jar`, a file tracked in git**. Rebuilding changes a committed artifact, so check whether the user wants the jar committed.
- Clean: `ant clean`
- Language level: the Docker image builds with JDK 15 (`openjdk:15-jdk-alpine`), so keep source compatible with Java 15. Don't use records, sealed types, or pattern-matching `switch`.
- For a quick compile without ant: `javac -d <outdir> -cp <lib jars> $(find src -name '*.java')`.
- Run from the repo root. `Config` reads the relative path `config/concord4pi.config`, and the app exits if `SerialDevice` is unset or the port can't be opened:
  ```
  java -Dtinylog.configuration=config/log.config -cp lib/jSerialComm-2.9.3.jar:jars/concord4pi.jar:lib/org.eclipse.paho.client.mqttv3-1.1.1.jar:lib/tinylog-api-2.6.0.jar:lib/tinylog-impl-2.6.0.jar concord4pi.concord4pi
  ```
  The ant build does not bundle the `lib/` jars into the output jar, so the full classpath is required. `startup/entrypoint.sh` uses this form. The classpath in the README, and `startup/start.sh` (jar only), are out of date.
- Docker: `docker build -t concord4pi .` builds with ant inside `openjdk:15-jdk-alpine`, and uses `sed` to switch `config/log.config` from file to console logging.
- Without hardware: `debug/setupDebugSerial` uses `socat` to create a linked pseudo-serial pair (`debug/SERIALIN` / `debug/SERIALOUT`) with the SB2000 line settings (9600 8O1). Point `SerialDevice` at one end and write to the other.
- When upgrading a dependency in `lib/`, update the versioned jar names in `startup/entrypoint.sh` (and in the README).

## Architecture

The work is split across three threads that communicate only through `ConcurrentLinkedQueue`s and poll in 50ms sleep loops:

1. **`SB2000Controller`** (`src/concord4pi/SB2000/`): owns the serial port through `SerialController`. A jSerialComm data listener appends raw bytes to a string buffer, guarded by a semaphore. The run loop frames messages: `MESSAGE_START`, then a 2-hex-char length, then the payload and checksum, all ASCII hex. It ACKs or NAKs each frame and pushes valid `IOMessage`s onto `rxQueue`. Outgoing `IOMessage`s go to `txVerifyQueue` until the panel ACKs them, and are retransmitted on NAK up to `maxReTransmit`. A `ControlMessage` is a single ACK/NAK byte.
2. **`MainController`** (main thread): drains `rxQueue` into `MessageProcessor`, collects inbound commands from every `IBroadcaster`, and queues any replies or commands back to `SB2000Controller`. At startup it sends `FULLCOMMAND_FullEquipmentList` so the panel reports all zones and partitions.
3. **Broadcasters** (`src/concord4pi/messaging/`, implementing `IBroadcaster`): `MQTTService` is currently the only one, and it is enabled by `MQTTBroadcaster=true`. Each broadcaster runs in its own thread and publishes queued `NotificationPacket`s. If `MQTTEnableCommands=true`, messages arriving on `<MQTTBaseTopic>/<MQTTCommandTopic>` are parsed as raw SB2000 hex commands, and a receipt is published under `<base>/<cmd>/receipt/{text,code,message}`. To add an output channel (the README mentions a REST API), implement `IBroadcaster` and register it in `MainController.addBroadcasters()`.

**Message dispatch:** `MessageProcessor.processMessage` switches on `IOMessage.getCommandCodeString()` against the `COMMANDCODE_*` constants in `SB2000Constants`. That class holds all protocol constants: command codes, zone, siren and trouble codes, and their text names. Codes starting with `0x22` or `0x23` are two-byte commands (see `IOMessage.MULTIBYTECOMMAND_PREFIX`). Each handler slices fields out of `getDataString()` by character offset (2 hex chars per byte). Text fields are sequences of 2-char tokens that are decoded through `SB2000Constants.convertTextToken`.

**`IOMessage` format:** the constructor takes **ASCII hex bytes**, not binary. For example, `new IOMessage("020204".getBytes())` gives length `02`, command `02`, data `04`. The ASCII hex string excludes the leading `MESSAGE_START` (`0x0A`) and includes the trailing checksum. The checksum is the low byte of the sum of the length, command, and data bytes. `isValid()` checks both the declared length and the checksum. A malformed input (odd length, non-hex, wrong length) doesn't throw. It produces an invalid message with an empty command code. `getMessageBytes()` is an unimplemented stub that returns `null`.

**State model and MQTT topics** (`src/concord4pi/data/`): `AlarmSystemData` is the root. It has an `AlarmPanel` and creates `AlarmAreaPartition`s lazily (`getAreaPartitionByID`) as the panel reports them. Partitions in turn own zones, touchpads, users, sirens, arming level, troubles, and so on. Every node implements `IAlarmObject`:
- `myAddress()` returns `parent.myAddress() + "/<segment>/" + ID`. This path becomes the MQTT topic suffix. IDs are the raw 4-hex-char strings from the panel, e.g. `/partitionarea/0001/zones/0005/status`. `MQTTService` publishes to `MQTTBaseTopic + suffix` with no separator added, because the suffix already starts with `/`.
- Setters call `updateTrigger(new NotificationPacket(myAddress() + "/field", value, action))`. The packet bubbles up through the parents to `AlarmSystemData.updateTrigger`, which hands it to every broadcaster. `MQTTService` skips `NPACTION_NEW` packets that have empty values. Every constructor fires one of these empty `NEW` packets.
- Lookups like `getZoneByID` and `getUser` **create** the child if it's missing, so a lookup on a mistyped ID silently adds a new node.
- The model has no hardware or network dependency. Construct `AlarmSystemData` with a stub `IBroadcaster` that records the packets, and you can drive it directly or through `MessageProcessor`. This is the easiest place to observe behavior.
- Naming convention: topics and log text say "partitionarea" (issue #7), even though the class is still named `AlarmAreaPartition`.

## Testability notes

- `LogEngine` ignores its `Config` argument, so `new LogEngine(null)` works. If tinylog has no configuration, it logs everything to the console at trace level. Pass `-Dtinylog.writer.level=error` (or supply a config) to quiet it.
- `SerialController`'s constructor opens the real port and calls `System.exit(0)` on failure. `MQTTService`'s constructor connects to the broker and also exits if it can't create the client. `SB2000Controller` builds its own `SerialController`. So nothing above the data model can be constructed in a test without a seam.
- `Config` reads a hardcoded relative path (`config/concord4pi.config`), and its `Properties` field is `static`.
- Several failure paths call `System.exit(0)`, so the process reports success even when startup fails.

## Known defects (verified 2026-10-23; remove entries as they're fixed)

Don't write tests or code that rely on these behaviors as if they were correct.
- **History lists are backwards.** Zone text, partition text, key fob commands, trouble events, and siren sync events append to the end of the list, and then trim by removing the *last* entry. Getters read index 0. As a result `getText()`, `getKeyFobCommandText()`, `getMostRecent*()` and similar return the *oldest* entry, and entries after the fifth are dropped. `AlarmZone.setKeyFobCommand` therefore publishes the wrong `/keyFob/command/text`. `AlarmZone.getPastText()` and `getPastKeyFobCommand()` throw on an empty list.
- **`AlarmTemperature.myAddress()`** uses `parent.getID()` instead of `parent.myAddress()`, which produces the topic `0001/temperature` (no leading `/` and no `partitionarea`).
- **`AlarmAreaPartition.getFeatureSetText()`** uses an `else if` chain, so it reports only the first set feature.
- **`AlarmArmingLevel.getDelayFlagsText()`** always reports `STANDARD` (it masks with 0). `END DELAY` can never match, because the mask and the flag are different bits. `setDelayFlags` publishes the number and the text to the same `/delayFlags` topic.
- **Radix:** `MessageProcessor` parses arming level (in `partitionData` and `armingLevel`) and feature state with radix 10. Every other field uses radix 16. Feature state values of `0x0A` and above decode wrong.
- **`MessageProcessor.timeAndDate`** passes the panel's 1-based month to `Calendar` (which is 0-based) and the 2-digit year as is.
- **`SB2000Controller` framing:** if leading bytes aren't `MESSAGE_START`, ACK, or NAK, the whole buffer is requeued, and processing waits until more serial data arrives. The leading garbage is then framed as a message and NAKed. Every pending `IOMessage` is written immediately, without waiting for each ACK. ACKs and NAKs are matched to `txVerifyQueue` by order alone.
- **`MainController.addBroadcasters()`** calls `.equals` on `MQTTBroadcaster` and `MQTTEnableCommands` without a null check. A missing key causes an NPE.

## Performance notes (current branch is `perf-improvements`)

- `LogEngine.log` calls `Thread.currentThread().getStackTrace()` **twice on every call**, before tinylog filters by level. Callers also build their message strings eagerly. Both costs are paid for TRACE and DEBUG messages even when those levels are off, and the serial and processing loops log heavily at those levels.
- Every thread polls with `Thread.sleep(50)`. `MQTTService.shutdown()` and `SerialController.closePort()` busy-spin on `while(isConnected())` and `while(isOpen())`.
- The serial receive buffer is a `String` that's concatenated on every serial event and re-sliced with `substring` for each frame.
- The README says MQTT client persistence is disabled, but `MQTTService` still uses `MqttDefaultFilePersistence("/tmp/concord4pi")`.

## Configuration

- `config/concord4pi.config` is a Java properties file read by `Config`. The README lists the keys. `Config.getProperty` can return `null`, and `MainController.addBroadcasters()` calls `.equals` directly on the `MQTT*` values, so a missing key can cause an NPE.
- `config/log.config` configures tinylog. `writer.level` is one of error/warn/info/debug/trace, and the system property `-Dtinylog.configuration` points to this file. Log calls go through `LogEngine.log(msg, Level)`.

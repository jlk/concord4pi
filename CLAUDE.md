# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

concord4pi bridges a Concord alarm panel's SuperBus 2000 (SB2000) Automation Module, connected over RS232 serial, to an MQTT broker. It publishes alarm state to MQTT topics and can optionally relay raw panel commands received over MQTT back to the panel. Plain Java, no framework, and no test suite.

## Build and run

- Build: `ant` (default target `dist`). This compiles `src/concord4pi` against every jar in `lib/` into `build/`, then writes **`jars/concord4pi.jar`, a file tracked in git**. Rebuilding changes a committed artifact, so check whether the user wants the jar committed.
- Clean: `ant clean`
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

**Message dispatch:** `MessageProcessor.processMessage` switches on `IOMessage.getCommandCodeString()` against the `COMMANDCODE_*` constants in `SB2000Constants`. That class holds all protocol constants: command codes, zone, siren and trouble codes, and their text names. Codes starting with `0x22` or `0x23` are two-byte commands (see `IOMessage.MULTIBYTECOMMAND_PREFIX`).

**State model and MQTT topics** (`src/concord4pi/data/`): `AlarmSystemData` is the root. It has an `AlarmPanel` and creates `AlarmAreaPartition`s lazily (`getAreaPartitionByID`) as the panel reports them. Partitions in turn own zones, touchpads, users, sirens, arming level, troubles, and so on. Every node implements `IAlarmObject`:
- `myAddress()` returns `parent.myAddress() + "/<segment>/" + ID`. This path becomes the MQTT topic suffix, e.g. `/partitionarea/1/zones/5/status`, published under `MQTTBaseTopic`.
- Setters call `updateTrigger(new NotificationPacket(myAddress() + "/field", value, action))`. The packet bubbles up through the parents to `AlarmSystemData.updateTrigger`, which hands it to every broadcaster. `MQTTService` skips `NPACTION_NEW` packets that have empty values.
- Naming convention: topics and log text say "partitionarea" (issue #7), even though the class is still named `AlarmAreaPartition`.

## Configuration

- `config/concord4pi.config` is a Java properties file read by `Config`. The README lists the keys. `Config.getProperty` can return `null`, and `MainController.addBroadcasters()` calls `.equals` directly on the `MQTT*` values, so a missing key can cause an NPE.
- `config/log.config` configures tinylog. `writer.level` is one of error/warn/info/debug/trace, and the system property `-Dtinylog.configuration` points to this file. Log calls go through `LogEngine.log(msg, Level)`.

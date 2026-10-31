# Unit testing: implementation plan

Status: ✅ Implemented 2026-10-23. 137 tests: 120 passing, 17 known-bug tests skipped. Still open:
- Commit the JUnit jar and the rest of the work (waiting for you to ask).
- ~~Run the tests on a real Java 21 JVM in Docker.~~ Done 2026-10-23 (see section 6). The project moved from Java 15 to 21 on 2026-10-23, after this plan was carried out.
- Add captured panel frames as fixtures (open question 1).

Created 2026-10-23.

## Goal

Build a unit test baseline that runs with one command and needs no serial hardware or MQTT broker. That way, changes on `perf-improvements` (and later branches) can be checked for behavior regressions.

Principles:
- **No production behavior changes.** The only source edits are small seams that let tests construct objects without opening a serial port, plus an injectable config path. Bug fixes are out of scope.
- **Known bugs are documented, not encoded.** Each defect listed in CLAUDE.md under "Known defects" gets a test that asserts the *correct* behavior and is marked `@Disabled("Known bug: ...")`. The suite stays green, and when a bug gets fixed, enabling its test is the check.
- **Keep the existing build working.** `ant` (the `dist` target) and the Docker build must behave exactly as they do today. Tests run only when you invoke `ant test`.

## 1. Toolchain ✅ Completed 2026-10-23

- [x] JUnit 5.13.4 (Jupiter) through `lib/test/junit-platform-console-standalone-1.13.4.jar`. It's downloaded, and its SHA-1 matches Maven Central. The baseline `ant clean compile` passes.
  - [x] Commit the jar, following the repo's convention of committing jars in `lib/`. *Done: committed with the test suite in `0ff0480`.*
  - Why 5.x and not JUnit 6: JUnit 6 requires Java 17, and the Docker image builds with JDK 15.
- [x] No mocking library. The fakes are hand-written, because only two are needed (a broadcaster and a serial controller).
- [x] Test sources must stay Java 15 compatible. *This is an ongoing rule, and section 6 verifies it.*
  - **Update 2026-10-23:** `openjdk:15-jdk-alpine` no longer exists, so the Docker image moved to `eclipse-temurin:21-jdk-alpine` (deployed on amd64). The target is now Java 21, enforced by `release="${java.release}"` in `build.xml`. JUnit 6 is now possible but isn't needed.

## 2. Build changes (`build.xml`, `.gitignore`) ✅ Completed 2026-10-23

- [x] Change the `compile` classpath from `lib/**/*.jar` to `lib/*.jar`, so the JUnit jar never ends up on the production classpath. This is now a shared `lib.classpath` path.
- [x] Add properties: `test.src=test`, `test.build=build-test`, `test.lib=lib/test`.
- [x] Add a `compile-test` target (depends on `compile`): compile `test/` against `build/`, `lib/*.jar`, and `lib/test/*.jar` into `build-test/`, then copy `test/tinylog.properties` into it.
  - `test/tinylog.properties` (console, level `error`) was created here instead of in section 4, because `compile-test` needs it.
- [x] Add a `test` target (depends on `compile-test`): run `org.junit.platform.console.ConsoleLauncher` in a forked JVM with `execute --scan-classpath build-test --fail-if-no-tests --details=tree`, and set `failonerror="true"` so failing tests fail the build.
- [x] Make `clean` also delete `build-test/`.
- [x] Add `build-test` to `.gitignore`.
- [x] Don't touch `dist`. `ant` must still produce `jars/concord4pi.jar` exactly as before, and must not run tests.
  - Also set `includeantruntime="false"` on the production `javac`, which silences the existing warning. The source doesn't use ant classes.

Verified: `ant clean` then `ant` succeeds, and the jar has the same 36 entries with no JUnit classes. `ant compile-test` succeeds. `ant test` fails with exit code 2 ("0 tests"), as expected until section 4 adds a test. The rebuilt jar was restored to the committed version afterward, because a jar built with JDK 27 won't run on the Java 15 runtime in Docker.

## 3. Production seams (minimal, no behavior change) ✅ Completed 2026-10-23

| File | Change | Why |
|------|--------|-----|
| `config/Config.java` | Add a `Config(String configFileName)` constructor. The no-argument constructor delegates to it with `config/concord4pi.config`. | Tests can load temp config files. |
| `SB2000/SerialController.java` | Add a `protected SerialController(LogEngine logger)` constructor that does *not* open a port. | A test subclass can capture writes. |
| `SB2000/SB2000Controller.java` | Add a package-private constructor that takes a `SerialController`. The public constructor delegates to it. | Tests can inject the fake serial controller. |
| `SB2000/SB2000Controller.java` | Move the body of `serialDataListener.serialEvent` into a package-private `appendReceivedData(byte[])`, and have the listener call it. | Tests can feed serial bytes without jSerialComm events. |
| `SB2000/SB2000Controller.java` | Change `processIncomingMessages()` and `processOutgoingMessages()` from `private` to package-private. | Tests can drive one loop iteration deterministically, without threads or sleeps. |

All five seams are in place.

Verified:
- `ant clean` then `ant` succeeds with no warnings. The jar was restored to the committed version afterward.
- `ant compile-test` succeeds.
- Smoke run: started the app from the repo root with the placeholder config. Both the modified build and a build of HEAD fail identically: the default config path is read, then the public `SB2000Controller` constructor and `SerialController` reject `/dev/[serial device name]`.

Found during the smoke run (existing behavior, left unchanged): a malformed `SerialDevice` name throws an uncaught `SerialPortInvalidPortException` from `SerialPort.getCommPort`. The friendly "could not be opened" message is never shown. This has been added to the known defects in CLAUDE.md.

## 4. Test layout and helpers ✅ Completed 2026-10-23

Tests live in `test/`, mirroring the package structure (`test/concord4pi/...`).

- [x] `test/tinylog.properties`: console writer at level `error`, so test output stays readable. *(Created in section 2.)*
- [x] `test/concord4pi/testsupport/RecordingBroadcaster.java`: an `IBroadcaster` that records every `NotificationPacket`. It has `packets()`, `topics()`, `published(topic)`, `valueOf(topic)`, `lastPacket(topic)` and `clear()`, plus a static `newAlarmSystem(recorder)` that builds an `AlarmSystemData` wired to it.
- [x] `test/concord4pi/testsupport/Messages.java`: `build(cmd, data)` returns a valid `IOMessage`, and `frame(cmd, data)` returns its ASCII hex. Both compute the length and checksum. `frameWithBadChecksum(...)` creates invalid frames, and `wire(...)` prefixes `MESSAGE_START` as the serial line delivers it.
- [x] `test/concord4pi/testsupport/FakeSerialController.java`: a subclass of `SerialController` that records writes as `"ACK"`, `"NAK"` or the message text, and ignores listener registration and port close.
  - It lives in `testsupport` instead of `SB2000`, because subclasses can call the protected constructor from any package. Tests of `SB2000Controller` still need to be in package `concord4pi.SB2000` to reach its package-private seams.
  - It records messages *without* the `MESSAGE_START` byte, because the real `SerialController.writeBytes(byte[])` prepends that, and the fake replaces that method.
- [x] Smoke test `test/concord4pi/testsupport/TestSupportTest.java`: 6 tests covering the helpers, including that `Messages.frame("02", "")` reproduces `FULLCOMMAND_FullEquipmentList`. These are kept as permanent tests.
- [x] `build.xml`: added `--disable-ansi-colors` and `--disable-banner` to the test launcher, so ant output is plain text.

Verified: `ant clean test` reports 6 tests found, 6 successful, BUILD SUCCESSFUL.

Note: on JDK 26 and later, the JUnit launcher prints a harmless `WARNING: Final field ... mutated reflectively` from its bundled command-line parser. The flag that silences it, `--enable-final-field-mutation`, doesn't exist on JDK 15, and an unknown JVM flag stops the JVM from starting. So the warning stays.

## 5. Test classes and cases ✅ Completed 2026-10-23 (137 tests: 120 passing, 17 known-bug tests skipped)

### `SB2000/IOMessageTest` ✅ Completed 2026-10-23 (16 tests; suite 22/22 passing)
- [x] Parses a single-byte command (`21`), including the length, command code string, and data string.
- [x] Parses two-byte commands with `22xx` and `23xx` prefixes.
- [x] A message with no data payload (for example `FULLCOMMAND_FullEquipmentList`, `020204`) is valid and has empty data.
- [x] Checksum: a valid message passes, a corrupted checksum fails, and a checksum whose byte sum exceeds 255 or crosses the signed byte boundary still validates.
- [x] A declared length that doesn't match the actual length (too long, or too short) is invalid.
- [x] Malformed input (odd number of characters, non-hex characters, empty input) is invalid and doesn't throw.
- [x] `toString()` round-trips to the uppercase ASCII hex input. Lowercase input is accepted.
- [x] Both `FULLCOMMAND_*` constants are valid messages.
- [x] Retransmit limit: `isReTransmittable()` stays true through 5 increments and is false after the 6th.

### `SB2000/SB2000ConstantsTest` ✅ Completed 2026-10-23 (5 tests; suite 27/27 passing)
- [x] `convertTextToken` (an unknown token returns `""`): spot-check digits (`00`→`0`), punctuation (`10`→`.`), letters (`20`→`P`), and word tokens (`41`→`BREEZEWAY`), plus the default case for an unknown token.

### `SB2000/SB2000ControllerTest` (uses `FakeSerialController`) ✅ Completed 2026-10-23 (14 tests, 1 disabled; suite 40 passing, 1 skipped)
- [x] One valid frame gets onto `rxQueue`, and one ACK byte is written on the next outgoing pass.
- [x] A frame with a bad checksum isn't queued, and a NAK is written.
- [x] Two frames in one chunk both get processed, in order.
- [x] A frame split across chunks (a partial length field, and a partial body) is held until complete, then processed once.
- [x] Outgoing `IOMessage`s are written as their hex text (`sendMessage` and `sendMessages`) and move to the verify queue. An incoming ACK removes them from it. (The `MESSAGE_START` prefix is added by the real `SerialController`, which the fake replaces. Testing that is out of scope.)
- [x] An incoming NAK requeues the head of the verify queue for retransmission. After 5 retransmits, the message is dropped.
- [x] A NAK or ACK with an empty verify queue doesn't throw. A loop pass with no new data does nothing.
- [x] `@Disabled` (known bug): leading junk before `MESSAGE_START` should be skipped without stalling and without sending a NAK. *Confirmed: it fails when run with `@Disabled` switched off.*

### `config/ConfigTest` (uses JUnit `@TempDir`) ✅ Completed 2026-10-23 (9 tests; suite 49 passing, 1 skipped)
- [x] Loads properties from a supplied file, and `getSerialDeviceName()` returns the configured value. Each new `Config` reloads from its own file, even though `prop` is static.
- [x] `valid()` is false when `SerialDevice` is missing or empty, or when the file doesn't exist. (A missing file prints a stack trace to the test output. That's existing behavior.)
- [x] `getPropertyInt` / `getDefaultLogLevel` parse numbers. A missing key throws an `IllegalArgumentException` that names the key, and a non-numeric value throws a `NumberFormatException`.
- [x] `getProperty` on a missing key returns `null`.

### `data/` model tests (use `RecordingBroadcaster`) ✅ Completed 2026-10-23 (65 tests, 14 disabled; suite 100 passing, 15 skipped)
- [x] `AlarmSystemDataTest`: topic addresses for the panel, partition, zone, user, siren, touchpad, arming level, and trouble event. `getAreaPartitionByID` and `getZoneByID` return the same instance for the same ID and create a new one for an unseen ID. Creating a node emits an empty `NPACTION_NEW` packet. Also covers `setDate` and the siren sync/go/stop fan-out to every partition.
- [x] `AlarmZoneTest`: status text for each flag and for combinations (`TRIPPED|FAULTED`), `NONE` for 0, type names, key fob command names (through the public API, using a fresh zone per command), `updateZoneData`, and the `/group/<n>` topic switching from NEW to UPDATE.
- [x] `AlarmAreaPartitionTest`: `isArea()` / `isPartition()` from the ID, feature-set text for single features, text, trouble events, user lookup, and temperature values.
- [x] `AlarmArmingLevelTest`: arming level text for area IDs versus partition IDs, the user being published to `/user`, the delay time, and the delay flags that currently work (the default and the exit bit).
  - `isArmed()` turned out to be inverted (see below), so it's covered by a disabled test instead.
- [x] `AlarmUserTest`: `getType()` across every range boundary (0, 229, 230, 237, 238, 245, 246–252, 253), and `isFob()`.
- [x] `AlarmPanelTest`: panel type names (`14`, `0B`, `1E`, `0E`, unknown), the published fields, and `getID()` / `toString()`.
- [x] `AlarmSirenTest`: cadence parsed from an 8-character hex string (including a high-bit byte), an invalid length leaving the cadence unchanged, go/stop state, repetition count, and the first sync event.
- [x] `AlarmTouchpadTest`: text decoded from tokens, and the broadcast flag text.
- [x] `AlarmTroubleTest`: source type text, general type text, and the published fields.
  - Every event in a partition publishes to the same `/partitionarea/<id>/event/...` topics, because `myAddress()` has no event ID. This is pinned as current behavior.

New defects found while writing these tests. Both are added to CLAUDE.md and have disabled tests:
- `AlarmArmingLevel.isArmed()` is inverted: it returns true for Zone Test or Off (level ≤ 1). It has no callers yet.
- `AlarmAreaPartition.isArea()` / `isPartition()` parse the hex ID with radix 10, so an ID containing A–F throws a `NumberFormatException`. `isArea()` runs every time arming-level text is built, so a partition or area of 10 or higher would crash message processing.

Verified with `@Disabled` switched off: all 15 disabled tests fail, each for its documented reason.

### `MessageProcessorTest` (real `AlarmSystemData` + `RecordingBroadcaster`) ✅ Completed 2026-10-23 (22 tests, 2 disabled; suite 120 passing, 17 skipped)
One test per handled command code. Each one checks the published topics and values and the returned responses.
- [x] `01` panel type, `02` automation event lost (returns two responses: the equipment list and the dynamic refresh), `03` zone data, `04` partition data, `21` zone status.
  - `04` decodes the partition's text tokens and then discards them: the text is never stored or published. That looks like an unfinished feature, and it's pinned as current behavior.
- [x] `2201` arming level including user, `2202` alarm/trouble (a zone source number is shortened from 6 to 4 hex characters, other sources are not), `2203` entry/exit delay time (16-bit), `2204` siren setup.
- [x] `2205`/`2206`/`220B` siren sync/go/stop are applied to every known partition.
- [x] `2209` touchpad display, `220C` feature state (values below `0x0A` only), `220D` temperature values, `220E` date published, `2303` key fob.
- [x] An unknown command code returns no responses, publishes nothing, and doesn't throw.
- [x] `buildZoneInventoryQuery()` returns the full-equipment-list command.

### Known-bug tests (all `@Disabled("Known bug: ...")`, assert correct behavior) ✅ Completed 2026-10-23 (17 tests)
These live in the test class for the code they cover, not in a separate class. With `@Disabled` switched off, all 17 fail for their documented reasons.
- [x] The zone and partition `getText()` return the most recent entry, and history keeps the newest 5. (`AlarmZoneTest`, `AlarmAreaPartitionTest`)
- [x] `AlarmZone.getText()` on a new zone returns `""` instead of throwing. (`AlarmZoneTest`)
- [x] The key fob `/command/text` matches the command just received. (`AlarmZoneTest`)
- [x] `getMostRecentAlarmTroubleEvent()` and `getMostRecentSirenSyncEvent()` return the newest entry. (`AlarmAreaPartitionTest`, `AlarmSirenTest`)
- [x] The temperature topic is `/partitionarea/<id>/temperature`. (`AlarmSystemDataTest`)
- [x] Feature-set text lists every set feature (for example `0x03` → `CHIME|ENERGY SAVER`). (`AlarmAreaPartitionTest`)
- [x] Delay-flag text reports extended / twice-extended and end delay correctly, and the number and text go to separate topics. (`AlarmArmingLevelTest`, 3 tests)
- [x] `isArmed()` is false when off and true when armed. (`AlarmArmingLevelTest`, found during this work)
- [x] Hex partition IDs (`000A`) don't throw in `isArea()` / `isPartition()`. (`AlarmAreaPartitionTest`, found during this work)
- [x] Feature state `0x40` decodes as QUICK ARM (radix). (`MessageProcessorTest`)
- [x] Time/date produces the correct month and a four-digit year. (`MessageProcessorTest`)
- [x] SB2000 framing skips leading junk. (`SB2000ControllerTest`)

## 6. Verification ✅ Completed 2026-10-23 (the Docker run was replaced by a compile-level check; see below)

- [x] `ant test` passes locally (Homebrew JDK 27 plus a scratchpad copy of ant): 137 found, 120 passing, 17 skipped, each shown with its "Known bug: ..." reason.
- [x] Java 15 compatibility: run `ant test` inside `openjdk:15-jdk-alpine` through Docker. *Superseded (2026-10-31): that image no longer exists and the target moved to Java 21. The real-JVM run below, on Temurin 21, is the check that counts.*
  - [x] Substitute check: compiled `src/` and `test/` with `javac --release 15`, which uses the Java 15 API and language level. Both compiled cleanly to class-file major version 59 (Java 15), and that build runs 120 passing / 17 skipped. It still hasn't been run on a real Java 15 JVM. To do that, start Docker and run:
    `docker run --rm -v "$PWD":/src:ro openjdk:15-jdk-alpine sh -c 'apk add -q apache-ant && cp -r /src /work && cd /work && ant clean test'`
  - **Update 2026-10-23:** that image no longer exists. The target is now Java 21, and `build.xml` compiles `src/` and `test/` with `release="21"`. To check on a real Java 21 JVM, run:
    `docker run --rm --platform linux/amd64 -v "$PWD":/src:ro eclipse-temurin:21-jdk-alpine sh -c 'apk add -q apache-ant && cp -r /src /work && cd /work && ant clean test'`
  - [x] **Done 2026-10-23 (Colima, emulated x86_64):** with Temurin 21.0.12.1 and Alpine's ant 1.10.18, that command gives 137 found, 120 passing, 17 skipped, BUILD SUCCESSFUL.
  - [x] Also built the real image with `docker build --platform linux/amd64 .` (succeeded, 387 MB). Then started the app inside it through `startup/entrypoint.sh`, with `SerialDevice=/dev/null`. Logging initialized, jSerialComm's native library loaded on Alpine/musl x86_64, and the app reached the friendly "Serial Port (/dev/null) could not be opened!" exit. The classpath in `entrypoint.sh` therefore matches the libraries in the image.
  - Warnings that existed before the Java 21 move:
    - Docker reported `JSONArgsRecommended`: the `ENTRYPOINT` used shell form, so the JVM didn't receive SIGTERM from `docker stop`. **Fixed 2026-10-23** with a JSON-form `ENTRYPOINT` plus `exec java` in `startup/entrypoint.sh`. Verified in Colima with `SerialDevice=/dev/ptmx`: java is PID 1, and `docker stop` returns in under a second (it was the 10 s timeout) with exit code 143. The log shows "Received termination signal" and "Serial Port (/dev/ptmx) CLOSED!".
    - The JVM warns that `libjSerialComm.so` 2.9.3 "might have disabled stack guard". This is still present and harmless.
- [x] `ant` (dist) still builds. `jars/concord4pi.jar` has 36 entries, with no JUnit, test or `testsupport` classes. The rebuilt jar was restored to the committed version afterward.
- [x] Temporarily broke the checksum calculation (`IOMessage.calculateCheckSum` returning +1): 17 tests failed and the ant build failed. After reverting, `git diff` on the file was empty and the suite was green again (120 passing / 17 skipped).

## 7. Docs ✅ Completed 2026-10-23

- [x] CLAUDE.md: replaced "no test suite" with a "Tests" section (how to run, layout, helpers, the `@Disabled` known-bug convention, coverage gaps). Rewrote the testability notes to list the seams. Defects found along the way were added to "Known defects" as they came up.
- [x] README: added a short "Running tests" section after "Building".

## Out of scope (possible follow-ups)

- `MQTTService`. It needs a broker or a Paho mock. A later refactor could pull the command-topic handling (`handleMessage`) and the `sendMessage` filtering into testable methods.
- `MainController` and `concord4pi` startup, `SerialController` against real or `socat` ports, and thread and timing behavior.
- Fixing any of the known bugs.
- Running tests in the Dockerfile build or in CI.

## Execution order

1. Do sections 1, 2 and 3 in order, and run `ant` after each one to confirm the build still works.
2. In section 4, the helpers have no tests yet, so check them with `ant compile-test`. A one-line smoke test then confirms the `ant test` wiring works.
3. In section 5, run `ant test` after each test class.
4. Do section 6 (mostly for the Java 15 check), then section 7.

## Open questions

1. Fixture data for `MessageProcessorTest` is built from the field offsets the parser uses, not from captured panel traffic. **✅ Resolved 2026-10-23:** a debug capture of 775 valid frames was replayed through `MessageProcessor` with no exceptions. Curated frames became `test/concord4pi/RealFramesTest.java` (15 tests, 3 disabled). Only non-identifying zone names were kept, and the `09` user-data frames were excluded because they appear to contain user codes. Then the capture file was deleted. The capture confirmed the handlers' field offsets, and it turned up the following:
   - the unhandled command codes listed in CLAUDE.md
   - word-token spacing and the unmapped `FB`/`FE` tokens
   - the delay flag masks being one bit low (the capture had no `01` panel-type frame, and the `AlarmArmingLevelTest` exit-bit test that asserted `0x20` was corrected to `0x40`)
2. ~~Should the Docker build run `ant test` too?~~ **Decided: no.** The Dockerfile stays unchanged.

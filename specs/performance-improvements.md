# Performance improvements: implementation plan

Status: ✅ **All sections done.** Created 2026-10-23 on branch `perf-improvements`; sections 1-3 landed 2026-10-23, sections 4-7 on 2026-10-24.

**Headline result** (end to end, HEAD vs this branch, real mosquitto broker, log level `info`, replaying frames captured from the panel):

| scenario | before | after |
|---|---|---|
| idle, left running | 1.02% of a core | 0.85% of a core (0.56% → 0.29% with JVM startup excluded) |
| 10 frames/s sustained, identical work | 9.78% of a core | **4.27% of a core** |
| 50 frames/s sustained | 18.7% of a core, and **only 17 frames/s processed** — permanently behind | 12.0% of a core, **46 frames/s processed** — keeping up |
| 60-frame burst (the startup equipment list) | 3.31 s to drain | **0.05 s** |
| `MessageProcessor` cost per frame | ~18 µs, ~48 KB allocated | ~0.5 µs, ~0.7 KB |
| CPU burned waiting during shutdown | 498 ms | 1 ms |

Behavior is unchanged throughout: 138 tests pass, the 23 known-bug tests still fail identically, and two differential transcripts (445 serial byte streams, 955 `IOMessage` inputs) are byte-identical against the pre-change build. The diff was then reviewed in full and four defects of its own were fixed, three changes reverted as unnecessary — see "Review of the uncommitted diff" near the end.

## Problem

The app consumes excessive CPU when left running. Code review (2026-10-23) found the causes fall into four groups: logging overhead paid even when the level is filtered out, sleep-polling and busy-spin loops, string churn in the serial receive path, and per-publish MQTT disk persistence. Ranked by expected impact below; the plan's sections are ordered the same way.

## Goal

Cut steady-state CPU substantially without changing observable behavior: same MQTT topics and values, same serial protocol handling (including the framing quirks the known-bug tests pin down), same shutdown semantics.

Principles:
- **No functional changes.** Bug fixes stay on the "Known defects" list in CLAUDE.md; this plan doesn't fix them, and must not accidentally change behavior a disabled known-bug test asserts against.
- **`ant test` stays green after every section.** The test seams (`SB2000Controller(SerialController, LogEngine)`, `appendReceivedData`, `processIncomingMessages`/`processOutgoingMessages`, `SerialController(LogEngine)`) must keep working. Tests call the process methods directly without threads, so loop/wakeup changes must not move logic out of those methods.
- **Don't commit `jars/concord4pi.jar`.** After any `ant` run, restore it with `git checkout -- jars/concord4pi.jar`.
- Measure before and after (section 7), so we know which fixes mattered.

## Findings (code review 2026-10-23)

1. **`LogEngine` walks the stack twice on every call, before level filtering** (`LogEngine.java:16,29`). `Thread.currentThread().getStackTrace()` materializes the full stack; it runs twice per log call regardless of whether the level is enabled. There are ~83 TRACE/DEBUG call sites, many in per-byte/per-frame paths: `appendReceivedData` logs twice per serial event, `processIncomingMessages` logs on every lock acquire/release and every frame, `MQTTService` logs per packet. Callers also build their message strings eagerly (`"..." + message + "..."`), including `IOMessage.toString()` calls that do `String.format("%02X")` per byte. All of this cost is paid at the default `info` level.
2. **Busy-spin waits.** `MQTTService.shutdown()` runs `while(clientConnection.isConnected());` — a full-core spin on the caller's thread until the MQTT run thread gets around to disconnecting. `SerialController.closePort()` runs `while(serialPort.isOpen());`. These burn CPU at shutdown (and the MQTT one spins the shutdown hook thread while the run loop is still inside its own 50ms sleep).
3. **Three threads poll in 50ms sleep loops** (`SB2000Controller.run`, `MainController.start`, `MQTTService.run`): 60 wakeups/second baseline even when fully idle, plus `MainController` allocates a fresh `ArrayList` every tick and only processes **one** rx message per tick, so a burst (the startup equipment list is ~60 frames) drains at 20 frames/second while the queues hold memory.
4. **Serial receive buffer is a `String` reparsed by copying.** `appendReceivedData` does `dataToProcess += new String(receivedData)` (full copy per serial event), and the framing loop in `processIncomingMessages` advances by `substring(...)` — one full-string copy per ACK/NAK byte and per frame. Cost is quadratic in burst size. The requeue paths copy the whole remainder again.
5. **MQTT publishes hit disk.** `MqttDefaultFilePersistence("/tmp/concord4pi")` with Paho's default QoS 1 persists every publish to disk. The README already claims persistence is disabled. Also, data-model setters publish on every set even when the value didn't change, so the panel's periodic refreshes (touchpad clock text updates every minute, zone/partition re-reports) each cost a full QoS 1 round trip + disk write.
6. **`IOMessage` parsing/formatting overhead.** Construction round-trips raw bytes → hex string → regex `split("(?<=\\G.{2})")` → bytes. `toString()` rebuilds the hex string (`String.format` per byte) every call, and it's called repeatedly in log strings for the same message.

## 1. LogEngine: stop paying for disabled log levels ✅ done 2026-10-23

The single biggest win. Keep the `log(String, Level)` / `logObject(Object, Level)` signatures so no caller or test breaks.

- [x] Check the level first (`Logger.isTraceEnabled()` etc. via a small `isEnabled(Level)` helper, or `org.tinylog.Logger` tag-less level checks) and return immediately when disabled — before any stack inspection or string work. `isEnabled(Level)` is **package-private** (narrowed after review 2026-10-24: it had no production caller, so it isn't public API — it's a seam for the log methods and tests, like the others).
- [x] When enabled, capture the caller **once**, using `StackWalker.getInstance().walk(...)` (cheaper than `getStackTrace()`, and we only need frame 2) instead of two full `getStackTrace()` calls.
- [x] Replace the `if(level.equals(...))` chain with a `switch` on the enum.
- [x] Add a lazy overload `log(Supplier<String> message, Level level)` for hot paths, so message strings aren't built when the level is off.
- [x] Migrate the hot-path call sites to the lazy overload or wrap them in a level guard: `SB2000Controller.appendReceivedData` and `processIncomingMessages` (all TRACE/DEBUG lines, especially the ones that stringify the buffer or an `IOMessage`), `SerialController` read/write TRACE lines, `MQTTService.run`/`sendMessage` DEBUG/TRACE lines, `MessageProcessor`'s 16 log calls, and the data-model setter TRACE lines. Cold paths (startup, errors) can stay as-is.
  - TRACE/DEBUG sites converted in `SB2000Controller`, `SerialController` and `MQTTService`. TRACE/DEBUG calls whose message is a constant string were left alone — with the level guard in place they cost only the `isEnabled` check and allocate nothing.
  - **The ~40 data-model conversions were reverted after review (2026-10-24).** Those setters run a handful of times per frame, so the saving was under 0.1% of the ~3.4 ms/frame total — unmeasurable — against 11 files of churn. `src/concord4pi/data/` is now untouched by this branch.
  - **Deferred message building must not change whether the app survives** (found in the same review): a Supplier only runs when its level is enabled, so a getter that throws — and several do, see "Known defects" — would have crashed the caller *only* at that level. `LogEngine.build()` and `write()` now contain a `RuntimeException` from building a message and log `<log message could not be built: ...>` instead. Two tests cover it (a throwing Supplier and a throwing `toString()`).
  - `MessageProcessor`'s calls were **not** converted: they are all INFO/WARN, which are enabled at the default level, so a Supplier would only add an allocation.
  - Three sites in `processIncomingMessages` needed a `final String remaining = dataProcessorString;` local, because the lambda can't capture the reassigned loop variable.
- [x] Tests: `ant test` green. Add a small `LogEngineTest` asserting the supplier isn't invoked when the level is disabled (run with `tinylog.writer.level=error`, pass a supplier that records invocation).
  - `test/concord4pi/logging/LogEngineTest.java`: supplier not invoked for TRACE-WARN, invoked for ERROR, `isEnabled` matches the configured writer level, and every overload still prefixes the message with the same caller name (the behavior the StackWalker change could have broken).

**Measured** (M4 Mac, JDK 24, `writer.level = info`, 1M calls of `log("Discovered new message [" + ioMessage + "]", Level.DEBUG)`):

| | per call |
|---|---|
| before | ~2.0 µs |
| after (first round, little JIT) | ~8 ns |
| after (warm) | eliminated entirely — the guard inlines and escape analysis removes the lambda |

The two `getStackTrace()` calls were ~0.9 µs each; the tinylog level check is ~25 ns. Real-world savings depend on the deployment's log level (open question 2) — at `info` this removes essentially all of the ~83 TRACE/DEBUG sites' cost, including the `IOMessage.toString()` work in section 6's finding.

## 2. Kill the busy-spins ✅ done 2026-10-23

- [x] `MQTTService.shutdown()`: replace `while(clientConnection.isConnected());` with a bounded wait (e.g. poll `isConnected()` with `Thread.sleep(50)` up to ~5s, then log a warning and continue). Keep the ordering: `isRunning = false` first so the run thread exits and disconnects.
  - `waitForDisconnect()` polls every 50ms up to `SHUTDOWN_TIMEOUT_MS` (5s), then logs WARN and lets shutdown continue. An interrupt re-sets the thread's interrupt flag and stops waiting rather than being swallowed.
- [x] `SerialController.closePort()`: replace `while(serialPort.isOpen());` with the same bounded-sleep wait. Note shutdown currently runs twice (known defect) — don't fix that here, just make each run cheap.
  - `waitForPortToClose()`, same shape and constants. After review (2026-10-24) the logging is either/or rather than both: "CLOSED!" at INFO only when the port actually reported closed, the WARN instead when it timed out. The first version logged "CLOSED!" unconditionally, which could assert something false. The double shutdown is untouched and still logs "CLOSED!" twice.
- [x] Tests: existing suite green; these paths aren't unit-covered (MQTT/serial), so verify by running under the socat debug harness and sending SIGTERM.
  - `ant test`: 136 passed, 0 failed, 21 known-bug tests still skipped.
  - socat isn't installed on this machine and Docker wasn't running, so the harness was a Python `pty.openpty()` pair instead (same idea, no install). The bridge opened the pty, was fed four real panel frames, processed and ACKed each, and exited 0.4s after SIGTERM with the expected (still doubled) "Serial Port ... CLOSED!".
  - The MQTT path needs a broker, so it was driven against a ~60-line fake MQTT 3.1.1 broker (CONNACK/SUBACK/UNSUBACK/PUBACK/PINGRESP) that **delays UNSUBACK on purpose**, which is exactly the window `shutdown()` waits through. CPU measured on the calling thread with `ThreadMXBean`:

| broker delays UNSUBACK by | shutdown() wall time | CPU burned by the caller |
|---|---|---|
| 2s, before | 2034 ms | **2021 ms** (a full core) |
| 2s, after | 2092 ms | **2 ms** |
| never answers, after | 5001 ms (timeout) | 6 ms, then WARN and continue |

  The last row is also a hang fixed, not just CPU: with the old spin, a broker that never completes the disconnect kept the shutdown thread in an unbounded loop.
  - **Re-verified against a real broker (mosquitto 2.1.2 in Docker, 2026-10-24)**, no artificial delay: `shutdown()` waits ~0.5s either way (the run thread's queue poll), burning **498 ms of CPU before vs 1 ms after**. Note the interaction: with section 3's 500ms poll in place, the old spin would have burned half a core-second on *every* shutdown, and shutdown runs twice (known defect). Landing section 2 before section 3 mattered.
  - Harness scripts were scratch files (`ptyrun.py`, `fakebroker.py`, `MqttShutdownBench.java`) and were not committed; the machine rebooted on 2026-10-24 and cleared them, so they'd need recreating to re-measure.

## 3. Replace sleep-polling with blocking waits ✅ done 2026-10-23

Keep the three-thread architecture and the package-private step methods; only change how the loops wait.

- [x] `SB2000Controller`: add a dedicated monitor (or `ReentrantLock`+`Condition`) that `appendReceivedData` and `sendMessage` signal. `run()` waits on it with a timeout (keep ~50ms–500ms as a fallback so NAK retransmit timing and anything else time-driven behaves the same), then calls the same `processOutgoingMessages()` / `processIncomingMessages()`. Tests keep calling the step methods directly, unaffected.
  - Monitor is `workSignal` + a `workPending` flag; `awaitWork()` waits up to `IDLE_WAIT_MS` (500ms), `signalWork()` wakes it. Signalled from `appendReceivedData` (after the semaphore is released), `sendMessage`, and `shutdown()` (so the loop sees `isRunning` without waiting out the timeout).
  - `awaitWork()` also returns immediately when `txQueue` is non-empty, so ACKs, NAKs and retransmits queued *during* a pass go out on the next pass instead of waiting for the timeout — same ordering as before, less latency.
- [x] `MainController.start()`: drain **all** waiting rx messages per iteration (loop on `alarmSystem.messageWaiting()`), not one; allocate `messagesToSend` only when there's something to send (or reuse one list and `clear()`). Then block instead of fixed-sleeping: simplest robust option is keeping a short `Thread.sleep` but only after an idle pass (no messages either direction); an event-driven handoff queue can come later if this isn't enough.
  - Drains in a `while(alarmSystem.messageWaiting())` loop, allocates the `ArrayList` lazily (stays `null` on an idle pass), and sleeps `IDLE_SLEEP_MS` (50ms) only when the pass moved nothing in either direction. The event-driven handoff is still deferred: this thread is the one that still wakes 20×/second when idle.
- [x] `MQTTService.run()`: swap `notificationQueue` from `ConcurrentLinkedQueue` to `LinkedBlockingQueue` and use `poll(500, TimeUnit.MILLISECONDS)` — no wakeups when idle, prompt delivery when busy, and the `isRunning` flag is still checked every timeout. `sendNotification` becomes `offer()` (unbounded, never blocks the model thread).
  - `sendNotification` already used `offer()`; only the field type and the run loop changed. Shutdown latency for this thread goes from ≤50ms to ≤500ms, well inside the 5s bounded wait from section 2.
  - **Interrupt handling was wrong in the first version and was fixed after review (2026-10-24).** Both new wait loops caught `InterruptedException`, re-armed the flag with `Thread.currentThread().interrupt()` and carried on — but a blocking call throws *immediately* when that flag is set, so the loop became a busy wait: measured 484,497 passes in 200ms (`MQTTService.run`) and 170,276 (`awaitWork`). Nothing interrupts those threads today, so it was unreachable, but it's the exact bug class section 2 removed. Both now log a WARN and end the loop (`isRunning = false`), which the MQTT one follows with its normal unsubscribe/disconnect. Verified on the real classes: the thread exits, 0 ms CPU. Note HEAD did *not* spin here — its catch didn't re-arm the flag, so its next `sleep()` blocked normally; HEAD also kept running after an interrupt, where these loops now stop.
- [x] Tests: suite green. `RecordingBroadcaster`/`FakeSerialController` don't touch these loops.
  - `ant test`: 136 passed, 0 failed. Known-bug tests still fail (21) under `--config=junit.jupiter.conditions.deactivate=org.junit.*DisabledCondition`.
  - Live run over the pty harness: identical to before — same four frames processed, same four ACKs on the wire, same startup `020204`, same 0.4s SIGTERM shutdown.

**Measured.** Idle CPU, all three threads live (pty serial + the fake MQTT broker connected), sampled as the CPU difference between a 10s run and a 40s run so JVM startup and shutdown drop out:

| | CPU per 30s idle | share of one core |
|---|---|---|
| before | 167 ms | 0.56% |
| after | 86 ms | 0.29% |

Roughly half, and what's left is mostly `MainController`'s 50ms idle sleep, which this section deliberately kept.

Burst throughput, 60 frames written to the serial line at once (the startup equipment list is about this size), timed from the bridge's own log until all 60 are processed:

| | time to drain 60 frames |
|---|---|
| before | 3.31 s (the predicted ~20 frames/second) |
| after | 0.05 s |

Harness scripts were scratch files (`idle.py`, `burst.py`, `ptyrun.py`, `fakebroker.py`) and were not committed; they were cleared by the 2026-10-24 reboot, so they'd need recreating to re-measure. If these measurements get repeated often, they belong in `debug/` instead.

## 4. Serial receive buffer: stop copying strings ✅ done 2026-10-24

Highest churn under real traffic. The framing *semantics* must not change — `SB2000ControllerTest` (including the disabled known-bug tests for the garbage-requeue behavior) is the guard.

- [x] Replace the `String dataToProcess` accumulator with a `StringBuilder` (append in `appendReceivedData`, `delete(0, n)`/index-based scan in `processIncomingMessages`), or equivalently a byte buffer. Scan with an index instead of `substring(1)` per control byte; take each frame with a single `substring(start, end)` only when handing it to `IOMessage`.
  - The buffer is now a `final StringBuilder` scanned in place with a `cursor`. Control bytes just advance the cursor; the only copies left are the one `substring` per frame handed to `IOMessage` (unavoidable — it takes a byte array) and the small `new String(receivedData)` per serial event.
  - `appendReceivedData` keeps `new String(receivedData)` rather than appending bytes as chars, because that decoding (platform charset) is what the current code does and changing it would change how non-ASCII line noise frames.
  - The length field is parsed with `Integer.parseInt(dataToProcess, cursor, cursor + 2, 16)` (the CharSequence overload, Java 9+), so no 2-char substring per frame, with identical exception behavior.
- [x] Preserve the exact current behaviors: requeue-with-prepended-`MESSAGE_START` on unknown leading bytes, requeue on short data, the `trim()` on extracted frame text, and the embedded-next-start recovery path.
  - All four paths go through one `requeueFrom(cursor)` helper: `delete(0, cursor)` then `insert(0, MESSAGE_START)`. The comment on it says plainly that prepending `MESSAGE_START` to a remainder that didn't start with one is the known defect, not an accident.
  - The TRACE lines in those paths log `dataToProcess.substring(1)` *after* requeueing, which is the same text the old code logged before it — still inside the lazy Supplier, so nothing is built when TRACE is off.
- [x] Keep the semaphore discipline between the listener thread and the processing thread (or switch both to the section-3 lock — either way, same mutual exclusion).
  - Unchanged: same semaphore, same acquire/release points, same early exit before the lock. Because the processor holds the lock for the whole pass, no append can land mid-scan, which is what makes the in-place scan safe (the old snapshot-and-clear was protecting against the same thing).
- [x] Tests: suite green, including re-running the launcher with `--config=junit.jupiter.conditions.deactivate=org.junit.*DisabledCondition` to confirm the known-bug framing tests still fail the same way (behavior unchanged).
  - `ant test`: 136 passed, 0 failed. Under the deactivate flag the known-bug tests still fail, including the two new ones below.
  - **Differential test** (the real check, not committed — `fuzz/concord4pi/SB2000/FramingTranscript.java` in the scratchpad): prints a transcript of rx messages, serial writes and buffer state for 445 byte streams — a valid frame, two frames in one chunk, a bad checksum, the same frame split at *every* offset in two and three pieces, bare ACK/NAK bytes, leading junk, doubled `MESSAGE_START`, non-hex length fields, embedded starts, empty chunks, and 400 fixed-seed fuzz cases over the alphabet the framer cares about. Run against the HEAD build and the new build, the transcripts are **byte-identical (0 diff lines)**.
  - **Correction (review 2026-10-24): that 0-diff result did not cover the throw-then-continue path**, because each scenario used a fresh controller. It differed. HEAD snapshotted the buffer and cleared it at the *start* of a pass, so a throw mid-scan discarded the bad bytes and the next pass started clean; scanning in place left them at the head of the buffer, and every later pass rethrew on them — the framer never made progress again. Fixed: the scan is wrapped so a `RuntimeException` clears the buffer before rethrowing, matching the old snapshot-and-clear. Verified with a dedicated check (feed a `00`-length frame, then a good frame): HEAD and this branch now behave identically, and both transcripts are still 0 diff.

**Found by the fuzzer, not caused by this work:** a frame whose length byte is `00` throws `ArrayIndexOutOfBoundsException` out of `IOMessage.initMessage` — `messageLength` 0 equals `encodedMessage.length - 1`, so the length passes validation and the code then reads `encodedMessage[1]` for the command code. HEAD does this too, identically. On the serial thread that kills the run loop, so the bridge stops reading the panel while still looking alive; with `MQTTEnableCommands=true` publishing `00` to the command topic reaches the same code on Paho's callback thread. Logged under "Known defects" in CLAUDE.md with two `@Disabled` tests (`IOMessageTest.zeroLengthMessageIsInvalidWithoutThrowing`, `SB2000ControllerTest.zeroLengthFrameIsNakedWithoutThrowing`). Worth fixing outside this plan — it's a crash, not a performance issue.

**Measured** (`BufferBench` in the scratchpad, best of 5 after warmup, allocation via `com.sun.management.ThreadMXBean`):

| scenario | before | after |
|---|---|---|
| one chunk of 100 frames | 1.74 ms / 887 KB | 0.83 ms / 221 KB |
| one chunk of 500 frames | 5.15 ms / 7.6 MB | 0.99 ms / 1.1 MB |
| one chunk of 2000 frames | 20.79 ms / 79.9 MB | 3.00 ms / 3.9 MB |
| 100 appends before one pass | 1.45 ms / 1.4 MB | 0.48 ms / 202 KB |
| 500 appends before one pass | 8.36 ms / 11.9 MB | 2.18 ms / 1.0 MB |
| 2000 appends before one pass | 33.02 ms / 122.3 MB | 2.83 ms / 3.9 MB |

The shape is the point: before, 20× the frames cost ~90× the allocation (quadratic); after, 20× the frames costs ~18× (linear). At the panel's real frame rate the absolute numbers are small — this matters for bursts and for GC pressure, not for steady-state CPU.

## 5. MQTT: memory persistence, cheaper publishes ✅ done 2026-10-24

- [x] Swap `MqttDefaultFilePersistence("/tmp/concord4pi")` for `MemoryPersistence` — this makes the code match what the README already says, and removes a disk write per publish. Message loss on crash is acceptable for state topics that get re-reported.
  - Done. The README's v0.3 changelog line about moving the persistence directory to `/tmp/concord4pi` is now historical only; left as-is because that section is a per-version changelog, not current documentation.
- [x] ~~Optional (ask before doing): publish state topics at QoS 0, or add a config key for publish QoS.~~ **Asked 2026-10-24: keep QoS 1.** No code change. Delivery stays acknowledged and nothing downstream sees a difference; the round trip per publish is accepted.
- [x] Out of scope (behavior change): suppressing publishes when the value is unchanged. Note it as a possible follow-up; it would change topic traffic that downstream consumers may rely on.
  - Still not done here, but **now unblocked**: the answer to open question 1 is that nothing downstream needs the repeats. Proposed as a follow-up (see "Possible follow-ups"), not folded into this section.
- [x] Tests: none cover MQTTService; verify against a local broker (or mosquitto in Docker) that topics still publish.
  - First verified against the fake MQTT broker from section 2, extended to decode PUBLISH and log every `topic / payload / qos`. The app ran over the pty harness and was fed five real panel frames (zone data, partition, zone status, arming, siren setup).
  - **Then re-verified against a real broker** once Docker became available: mosquitto 2.1.2 in a container, subscribed with `mosquitto_sub -t 'concord4pi/#' -v`. Same 19 lines as the fake broker produced — 15 state topics plus the 4 from an inbound command round trip (`concord4pi/cmd` → `receipt/text`, `receipt/code=success`, `receipt/message`), which also exercises the `MQTTEnableCommands` subscribe path.
  - **The publish stream is byte-identical between the HEAD build and the new one**, against both the fake broker and real mosquitto: same topics, same order, same payloads, same QoS 1 — from `.../zones/0001/text = FRONTDOOR` through `.../armingLevel/text = Away` to `.../siren/cadence/binary`.
  - `/tmp/concord4pi` is created by the HEAD build and **not** by the new one, which is the disk write per publish going away.

**Unrelated observation from the capture:** `partitionarea/0100/siren/cadence` publishes `-1434451968` — the 32-bit cadence mask (`0xAA800000`) rendered as a signed decimal. The companion `/siren/cadence/binary` topic carries the readable bits, so it's awkward rather than wrong. Not touched, not added to the defect list; worth a look if a consumer ever reads that topic.

## 6. IOMessage: cheaper parse/format ✅ done 2026-10-24

Lower priority; do after 1–4 land, since section 1 already stops most `toString()` calls from happening.

- [x] Replace the regex `split("(?<=\\G.{2})")` in `convertHexStringToEncodedByteArray` with a direct two-chars-at-a-time parse loop.
  - Loop over `message.length() / 2`, parsing each pair with `Integer.parseInt(message, offset, offset + 2, 16)` — the CharSequence overload, so no substring per byte either.
  - **Deliberately not a hand-rolled nibble decoder.** `Integer.parseInt` accepts things a nibble decoder would reject, and that acceptance is observable: `+5` parses as length 5, `-1` as `FF`. Both appear in the differential transcript below. Keeping `parseInt` keeps those identical.
  - `convertRawByteArrayToHexString` also stopped allocating a `char[]` and a `String` per byte pair; it appends the two chars straight into the builder.
- [x] Cache `toString()` (the message is immutable after construction) and build it with a hex-nibble lookup instead of `String.format` per byte.
  - `toString()`, `getCommandCodeString()` and `getDataString()` are all built on first use and cached. Safe because the fields are set in `initMessage` and never change, and the byte arrays never escape the class — nothing extends `IOMessage`, and nothing outside it calls `getData()`/`getCommandCode()` (checked). `transmissionCount` does change, but no cached string renders it.
  - Caching the two getters beyond `toString()` is a small scope addition, and it's where the win is: `MessageProcessor` dispatches on `getCommandCodeString()` and then slices `getDataString()` repeatedly — 13 call sites — each of which used to rebuild the whole hex string with `String.format` per byte.
  - Formatting is now a `HEX_DIGITS` nibble lookup in `convertEncodedByteArrayToHexString`, the method that runs per byte of every message. `String.format("%02X", aByte)` formats a `Byte` *unsigned*, so the lookup matches for all 256 values — verified explicitly, not assumed.
  - `convertByteToStringHex` was also rewritten at first and has been **reverted to `String.format` after review (2026-10-24)**: its only caller is `getMessageLengthString()`, which only a test assertion uses, so the rewrite had no production value.
- [x] Keep malformed-input behavior identical: no throw, invalid message with empty command code. `IOMessageTest` is the guard.
  - `ant test` green, and the 23 known-bug tests still fail under the deactivate flag — including the zero-length `00` crash from section 4, which this rework deliberately did **not** fix.
  - **Differential transcript** (`fuzz/concord4pi/SB2000/IOMessageTranscript.java`, not committed): for 955 inputs it prints `isValid`, command code, data, length (both forms), calculated length, both checksums, `toString()` called three times (proving the cache returns the same value, including after `incrementTransmissionCount()`), the encoded bytes, `getMessageBytes()`, and any throw. Inputs cover valid frames of each command shape, **all 256 byte values** through the length field, all 256 through a valid frame's data, 27 hand-picked malformed strings (empty, odd-length, non-hex, `+5`, `-1`, `+50000`, `-10000`, `" 5"`, `"5 "`, `0x11`), the shipped command constants, and 600 fixed-seed fuzz strings over an alphabet including `+`, `-` and space. HEAD vs new: **0 diff lines.**
  - Cross-check: section 4's 445-stream framing transcript was re-run after this change and is still **0 diff lines** against HEAD, so the parse/format rework didn't disturb framing either.

**Measured** (best of 5 after warmup, 200k iterations, allocation via `com.sun.management.ThreadMXBean`):

| scenario | before | after |
|---|---|---|
| construct 200k messages | 349 ms / 780 MB | 53 ms / 78 MB |
| `toString()` 200k times, one message | 551 ms / 2.25 GB | **1.2 ms / 0 KB** |
| `getDataString()` 200k times | 678 ms / 2.10 GB | **1.2 ms / 0 KB** |
| dispatch + 4 data slices, 200k frames | 3593 ms / 9.4 GB | 105 ms / 133 MB |

The last row is the realistic one — it's what `MessageProcessor` does per frame: **18 µs and 48 KB per frame before, 0.5 µs and 0.7 KB after.** The allocation reduction is the real story here; this was the largest garbage producer in the processing path.

## 7. Measurement and verification ✅ done 2026-10-24

- [x] Before starting: baseline CPU with the socat debug harness (`debug/setupDebugSerial`), replaying a captured frame sequence (reuse `RealFramesTest` frames) in a loop at realistic rates, measuring with `top`/`ps` over a few minutes. Record numbers in this file.
  - **This was never done before starting** — the work began without a baseline. Taken retroactively instead by building the pre-change tree from git HEAD (`git archive HEAD`) and measuring it with the identical harness, which is the same thing measured a day late. Worth doing in the right order next time.
  - Harness: a Python `pty.openpty()` pair instead of socat (not installed), 13 verbatim `RealFramesTest` frames replayed in a loop (zone status, the arming cycle with its delay messages, zone bypass and arming-protest events, zone/partition data, siren setup and sync, and the long touchpad-clock frame), a real mosquitto 2.1.2 broker in Docker, log level `info`, and a reader thread draining the bridge's ACKs so the pty never blocks. CPU came from `wait4` rusage over a fixed 120s (60s for the 50/s runs) rather than eyeballing `top`.
- [x] After each section lands: re-measure the same scenario; note the delta here.
  - Measured per section against HEAD rather than cumulatively (each section's own table above). The end-to-end numbers are in the headline table at the top of this file — worth noting those are *not* the sum of the per-section wins, because the sections overlap.

| run | before (HEAD) | after (branch) | messages processed |
|---|---|---|---|
| idle, 120s | 1.23s CPU (1.02%) | 1.02s CPU (0.85%) | n/a |
| 10 frames/s, 120s, 1200 frames | 11.73s CPU (9.78%) | 5.12s CPU (4.27%) | 1115 log lines **both** — same work, 56% less CPU |
| 50 frames/s, 60s, 3000 frames | 11.23s CPU (18.71%) | 7.23s CPU (12.04%) | 1012 vs **2776** — see below |

  The 50/s row is not an equal-work comparison and shouldn't be read as one. HEAD processed ~17 frames/s, which is its `MainController` ceiling (one rx message per 50ms pass = 20/s, finding 3) — so it fell permanently behind, with the rx queue backing up for the whole run. The branch processed ~46 frames/s and kept up. HEAD used *more* CPU to do *less than half* the work.
- [x] Also measure the idle case (no serial traffic) before/after section 3 — that isolates the polling cost.
  - Done in section 3 with a startup-excluded differential (10s vs 40s runs): **167 ms → 86 ms of CPU per 30s idle**, i.e. 0.56% → 0.29% of a core. The 120s totals in the table above include ~0.5s of JVM startup in both columns, which is why the idle improvement looks smaller there. Both numbers are real; the differential is the one that isolates polling.
- [x] Final check on the deployment target: Docker build, run, `docker stop` clean shutdown (watch for the double "Serial Port ... CLOSED!" — still expected, see known defects).
  - Done 2026-10-24. `docker build -t concord4pi:perf .` succeeds with these changes, and the local `jars/concord4pi.jar` stays untouched (ant rebuilds it inside the image layer only). The image was run with `socat` making a pty inside the container and MQTT pointed at the host broker: java is PID 1, the port opens, frames fed into the pty are processed, and topics reach the real broker from inside the container.
  - `docker stop`: exit code **143** (SIGTERM handled, never SIGKILLed) in all 6 runs, 3 per image.

| | `docker stop` duration | "CLOSED!" lines |
|---|---|---|
| HEAD image | 0.15 – 0.18 s | 2, 1, 1 |
| this branch | 0.61 – 0.69 s | 2, 2, 2 |

  Two honest notes. (1) Shutdown is ~0.5s slower, which is exactly section 3's 500ms MQTT queue poll being waited out; it's still ~15× inside Docker's 10s grace, and the lever if it ever matters is `QUEUE_WAIT_MS` or interrupting the run thread on shutdown. (2) The double-close known defect turns out to be a **race, not a certainty** — HEAD shows one or two lines depending on whether the main thread reaches its second `shutdown()` before the JVM finishes exiting. On this branch the longer window makes two lines consistent. Still harmless (the second close succeeds), but CLAUDE.md's wording was too definite and has been corrected.
- [x] After all sections: `ant test` green, disabled known-bug tests still fail under `--config=junit.jupiter.conditions.deactivate=org.junit.*DisabledCondition`, and `git checkout -- jars/concord4pi.jar`.
  - `ant test`: **136 passed, 0 failed, 23 skipped.**
  - Deactivate flag: **136 passed, 23 failed** — every known-bug test still fails, including the two added on 2026-10-24 for the zero-length-frame crash.
  - `jars/concord4pi.jar`: **unmodified** (`git status --porcelain jars/` is empty). No `git checkout` was needed — only `ant test` was ever run, which compiles but doesn't reach the `dist` target that rewrites the jar. The Docker build rewrites it inside the image layer only.

**What's left to do by hand:** nothing in this plan. Not done, by design: the zero-length-frame crash (correctness, on the known-defects list by request) and skip-unchanged publishing (see "Possible follow-ups"). The measurement harnesses live in the session scratchpad and are not committed; if these runs get repeated, `load.py`, `FramingTranscript.java` and `IOMessageTranscript.java` are the ones worth moving into `debug/`.

## Review of the uncommitted diff (2026-10-24)

The whole uncommitted change set was reviewed against "appropriate, correct, needed" before committing. Outcome:

- **Fixed (correctness):** the interrupt busy-spins in both new wait loops (section 3), and the receive buffer not being cleared when framing throws (section 4). Both were introduced by this work; both are now verified against the pre-change behavior.
- **Fixed (accuracy):** `closePort()` no longer logs "CLOSED!" when the port never reported closed (section 2), and a message expression that throws can no longer crash the caller, or crash it only at one log level (section 1).
- **Reverted as unnecessary:** the ~40 data-model lazy-log conversions (unmeasurable, 11 files of churn), `isEnabled()` as public API (narrowed to package-private), and the `convertByteToStringHex` rewrite (only a test reaches it).
- **Left as is, by decision:** `QUEUE_WAIT_MS = 500` (0.5s slower shutdown, well inside the 10s Docker grace); `if(messagesToSend != null)` in `MainController`, which can call `sendMessages` with an empty array where HEAD skipped it (harmless no-op); and the `IOMessage` string caches depending on `protected byte[]` fields not being mutated (true today, documented in CLAUDE.md).
- **Noted, pre-existing, untouched:** `MainController`'s idle `Thread.sleep` still catches `InterruptedException` with `e.printStackTrace()` and continues. It doesn't spin (the throw clears the flag) and it predates this work, but it's now the only one of the three loops whose interrupt handling doesn't stop the thread.

## Possible follow-ups (not in this plan)

- **Suppress publishes when the value hasn't changed.** Unblocked by the answer to question 1 below. The panel re-reports constantly (touchpad clock text every minute, zone/partition re-reports), and each one currently costs a full QoS 1 round trip even when the value is identical. This is a behavior change to the topic traffic, so it wants its own section and its own before/after capture with the fake broker.
- **Fix the zero-length-frame crash** found while verifying section 4 (`00` length byte throws out of `IOMessage` and kills the serial thread). Correctness, not performance; left on the known-defects list by request.

## Open questions

1. ~~Is anything downstream relying on the current republish-every-report MQTT behavior (e.g. Home Assistant availability/freshness)?~~ **Answered 2026-10-24: no, nothing relies on the repeats**, so skip-unchanged publishing is safe to pursue as a follow-up.
2. ~~What log level does the production Docker container actually run at?~~ **Answered 2026-10-24: `info`**, the committed default. So section 1's measurements apply as measured: the ~83 TRACE/DEBUG call sites cost essentially nothing now, and logging was not the bulk of the production CPU.
3. ~~Should publish QoS become configurable (section 5)?~~ **Answered 2026-10-24: no.** Staying on QoS 1.

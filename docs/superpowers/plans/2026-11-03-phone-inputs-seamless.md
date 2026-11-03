# Seamless phone mic + camera Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phone mic/camera become the laptop defaults on connect and keep serving laptop apps on demand while FoldDeck is backgrounded/locked, with lower start-up latency and no in-app live badge.

**Architecture:** Phone sends its sharing mask from switches + permissions only; a microphone|camera foreground service keeps the process eligible in background; when hidden the stream pauses video (new `MSG_VIDEO`, host stops its encoder) instead of closing the TLS socket. Host demand detection becomes event-driven (`pactl subscribe`).

**Tech Stack:** Python 3 host (PipeWire/pipewire-pulse CLIs), Android Java without Gradle (aapt2/javac/d8).

**Spec:** `docs/superpowers/specs/2026-11-03-phone-inputs-seamless-design.md` (brief: `docs/BRIEF-phone-inputs-seamless.md`)

## Global Constraints

- Branch `phone-inputs-seamless` only; small commits; never push; never touch main.
- Never uninstall the app or clear its data; `install -r` only; signature mismatch → stop and report.
- Only `pm grant` RECORD_AUDIO / CAMERA / POST_NOTIFICATIONS; don't touch lock/PIN or other apps.
- versionCode 26, versionName 0.13.0.
- Keep the concise `    sharing: ...` host log lines.
- Host tests: `cd host && python3 test_<name>.py` (all must pass). JVM checks: compile `android/test/*.java` against `android/build/classes` + android.jar, run from repo root (all must pass).

## Review Focus

- Host restarts while the phone is paused in background: the H264Stream retry loop reconnects without a visible activity; the new connection must come up paused (send `MSG_VIDEO off` + no `MSG_AUDIO on`) and sharing must reconfigure without starting a new FGS. Covered by Task 4 check + Task 7 device step.
- Rapid `MSG_VIDEO off`/`on` (app switch and back within one frame): host must not treat its own encoder kill as "capture ended" and drop the connection. Task 3 test.
- Surface destroyed while the pump thread is configuring the decoder: decoder must never render into a dead Surface (configure and release serialised; configure requires a surface). Task 4.
- Window focus changes while a laptop app records (split screen, notification shade): must not reconfigure sharing (a reconfigure bumps the revision and stops the mic). Task 5 check.
- POST_NOTIFICATIONS denied: service still runs, no repeated permission dialog loop, no reconfigure triggered by a notification-only permission result. Task 5.

---

### Task 1: Host restores defaults on SIGTERM

**Files:** Modify `host/stream.py` (`pump`, `serve`, `main`); Test `host/test_accept.py` (or new `host/test_shutdown.py`).

**Interfaces:** Produces `stream._session_cleanups: list[Callable[[], None]]`; `pump()` appends `shared.close` and `route.stop` on entry and removes them in its `finally`; `serve()`'s `finally` runs every registered cleanup. `main()` installs `signal.signal(SIGTERM, lambda *_: sys.exit(0))`.

- [ ] Write `test_shutdown.py`: register two fake closers in `stream._session_cleanups`, call `stream.close_sessions()`, assert both ran once and the list is empty; run under `subprocess` a tiny script that installs the handler via `stream.install_signal_handlers()`, registers a closer that writes a marker file, sends itself SIGTERM, assert marker exists.
- [ ] Run → FAIL (no such names).
- [ ] Implement `close_sessions()` and `install_signal_handlers()` (SIGINT + SIGTERM → `sys.exit(0)`), call `close_sessions()` from `serve()`'s `finally`.
- [ ] Run test → PASS; all host tests pass.
- [ ] Commit `host: restore phone defaults and laptop audio on SIGTERM`.

### Task 2: Event-driven demand detection

**Files:** Modify `host/demand.py`, `host/sharing.py`; Test `host/test_demand.py`.

**Interfaces:** `demand.is_capture_event(line: str) -> bool` (True for `Event '<new|change|remove>' on source-output #N`, False for client/sink/other). `demand.Watcher(callback)` runs `pactl subscribe` on a daemon thread, calls `callback()` per capture event, `close()` kills it. `Sharing.wake()` sets `self.burst_until = monotonic() + 0.3` and notifies; worker polls demand at 50 ms intervals while `now < burst_until`, else every 0.5 s; camera `usage.poll()` checked every pass and a change forces `poll_demand()`. `Sharing.__init__` starts a `Watcher(self.wake)`; `run()`'s `finally` closes it.

- [ ] Tests: `is_capture_event` on the real lines seen on this laptop (`Event 'new' on source-output #102736` → True, `Event 'new' on client #102735` → False); a `Sharing` worker with a fake MIC device and `demand.microphone_used` patched to flip True after start: `wake()` then assert a `DEMAND` with mask 2 was sent within 0.15 s (`time.monotonic()` measured).
- [ ] Run → FAIL.
- [ ] Implement.
- [ ] Run → PASS; all host tests pass.
- [ ] Commit `host: event-driven phone mic/camera demand detection`.

### Task 3: Host `MSG_VIDEO` pause/resume

**Files:** Modify `host/stream.py` (`MSG_VIDEO = 9`, `_PAYLOAD_LEN`, `input_reader(..., on_video=None)`, `pump`); Test `host/test_sharing.py`.

**Interfaces:** Wire: `[9, on:u8]` phone→host. `input_reader` calls `on_video(payload[0] == 1)`. In `pump`, `on_video(False)` marks the kill as a pause then kills the capture proc; the loop treats the resulting EOF as a pause, waits (checking `stop`) until `on_video(True)`, then spawns a fresh capture. A real capture EOF still ends the connection.

- [ ] Test: framed `b"\x09\x00"` and `b"\x09\x01"` through `input_reader` reach `on_video` as `[False, True]`; `pump`'s pause helper: extract `class VideoGate` with `pause()`, `resume()`, `consume_pause() -> bool` (True once after a pause-kill) and `wait(stop: Event) -> bool`; test that `pause(); resume()` before the EOF is noticed still yields `consume_pause() is True` (rapid toggle does not end the connection).
- [ ] Run → FAIL; implement; run → PASS; all host tests pass.
- [ ] Commit `host: MSG_VIDEO pauses screen capture for a sharing-only phone`.

### Task 4: Phone stream pause/resume

**Files:** Modify `android/src/io/folddeck/spike/H264Stream.java`; Test `android/test/StreamPauseCheck.java`.

**Interfaces:** `void pause()` (UI thread): `paused = true`, synchronously release decoder, null the surface, `sendVideo(false)`, `sendAudio(false)`. `void resume(Surface s)`: set surface, `paused = false`, `sendAudio(Prefs.laptopAudio(prefs))` only if true, `sendVideo(true)`. `boolean isPaused()`. `boolean sameRoute(String host, int port)`. On (re)connect: if paused send `sendVideo(false)` and skip `sendAudio(true)`. Decoder configure only when the NAL just added is a PPS, `surface != null`; `configure()`/`releaseCodec()`/surface swap synchronized.

- [ ] Check (JVM, reflection on `outbox.takeAll(0)`): fresh stream with a stub SharedPreferences (laptop_audio=true): `pause()` queues `{9,0}` then `{6,0}`; `resume(null)` queues `{6,1}` then `{9,1}`; `isPaused()` toggles.
- [ ] Run → FAIL; implement; build; run → PASS.
- [ ] Commit `android: pause stream video without dropping the connection`.

### Task 5: Ungated sharing, no badge, foreground service

**Files:** Modify `PhoneSharing.java`, `MainActivity.java`, `AndroidManifest.xml`, `android/test/SharingCheck.java`, `android/test/ManifestContractCheck.java`; Create `android/src/io/folddeck/spike/SharingService.java`.

**Interfaces:**
- `PhoneSharing`: delete `foreground`, `foreground(boolean)`, `stopInputs()`; mask = `wanted` filtered by permissions; `updateCapture` ungated except `supported`; clipboard paths gated on `focused`; laptop clipboard text received while unfocused kept in `deferredText`, applied in `focus(true)`; `focus()` no longer calls `configure()`. `boolean keepsConnection()` = stream attached and mic or camera enabled+permitted. `updateService()` starts/stops `SharingService` with `EXTRA_TYPES` = FGS type bits for enabled+granted inputs; called from `attach`, `toggle`, `permissionResult`; `disconnect` stops it. `requestAllowedPermissions()` adds POST_NOTIFICATIONS once (pref `asked_notifications`); `permissionResult()` returns early when no mic/camera bit was pending.
- `MainActivity`: remove `sharingBadge` and its theme code; `pauseStream()` = release held input + `stream.pause()` if `sharing.keepsConnection()`, else `dropStream()`; `surfaceDestroyed` and `relock` call it; `startStreamIfReady` resumes a paused stream on the same route; drop `sharing.foreground()` calls.
- `SharingService`: `onStartCommand` → channel `phone_sharing` (IMPORTANCE_LOW) → `startForeground(1, notification, types)`; `START_NOT_STICKY`; catches `RuntimeException` → `stopSelf()`.
- Manifest: FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_CAMERA, POST_NOTIFICATIONS; `<service android:name=".SharingService" android:exported="false" android:foregroundServiceType="microphone|camera"/>`; versionCode 26 / 0.13.0.

- [ ] Checks: `ManifestContractCheck` asserts the four permissions, the service with `microphone|camera`, version 0.13.0; `SharingCheck` grep-style asserts `MainActivity` has no `sharingBadge` and `PhoneSharing` has no `stopInputs`/`foreground` gate (source text check, the only practical JVM check for UI code).
- [ ] Run → FAIL; implement; build; run all checks → PASS.
- [ ] Commit (may split: badge removal / gating / service).

### Task 6: Mic start latency, pre-warm and AudioSource decisions

**Files:** Modify `PhoneSharing.java` (`startMic`, `stopMic`).

**Interfaces:** `startMic()` spawns the capture thread which constructs + starts `AudioRecord` and logs `mic: create N ms, start N ms, first read N ms` (tag `FoldDeck/Share`); `stopMic()` unchanged contract (`microphone == null` when idle, read by UiSmoke).

- [ ] Implement, build, install, run `latency-check.py --sessions 3`, read logcat timings.
- [ ] Decide pre-warm: if create ≥ 50 ms and `cmd appops get io.folddeck.spike RECORD_AUDIO` shows no running op while an un-started AudioRecord exists, pre-create on READY; else skip and record why.
- [ ] Decide source: build MIC / UNPROCESSED variants, measure peak/RMS against the same stimulus; keep the best non-clipping; adjust host volume.
- [ ] Commit with the numbers in the message.

### Task 7: Docs and acceptance verification

**Files:** `README.md` (Phone sharing bullet + requirements), `docs/NOTES.md` (short entry).

- [ ] README: describe ungated defaults, background/locked behaviour, notification, no badge, Hermes note.
- [ ] Verify A–G on the device with command output (latency-check `--background`, host stop/restart, camera consumer with `gst-launch-1.0` + `dumpsys media.camera`, uiautomator dump for no badge, all tests, `dumpsys package` versionName).
- [ ] Commit docs.

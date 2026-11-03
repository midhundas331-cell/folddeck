# Brief: seamless phone mic + camera (from Agent Boss / Midhun)

Repo: /home/midhun/folddeck  (branch phone-inputs-seamless; main has commit 542d23a "WIP 0.12.2 snapshot")
Use the superpowers workflow: the brainstorming decisions below are ALREADY MADE by the user -- do not
re-ask them. Write a short spec + plan into docs/, then implement with test-driven-development, then
verification-before-completion. Commit in small steps on this branch. Never push. Never touch main.

## User requirements (verbatim intent)
1. When the Fold connects in FoldDeck, the phone mic becomes the laptop's default microphone
   automatically, and the laptop falls back to its previous default when FoldDeck disconnects.
2. Pressing Ctrl+B in Hermes voice mode (records from the PipeWire default source via
   sounddevice/PortAudio) must record from the phone with no user action on the phone.
3. Remove the in-app "Microphone live / Camera live / Mic + camera live" badge/bar entirely, including its
   tap-to-stop behaviour (MainActivity sharingBadge -> sharing.stopInputs()). Android's own privacy
   indicators are enough. The user does not want the app telling him the mic/camera is on.
   Enabling/disabling remains only via FoldDeck Settings -> Phone Sharing switches.
4. Camera behaves the same as mic: becomes the laptop default camera on connect; phone streams camera
   frames only while a laptop app is actually consuming it (existing demand/lease model).

## Decisions made
- Background: YES. Phone mic/camera must keep working when FoldDeck is not on screen (other app, or
  locked?). Implement an Android foreground service with foregroundServiceType="microphone|camera"
  (+ FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_CAMERA, POST_NOTIFICATIONS
  as needed for targetSdk) and the mandatory system notification. User accepted that notification.
  Note Android rules: a microphone/camera FGS must be STARTED while the app is in the foreground
  (while-in-use permission); starting it from the background throws. Start it when the stream connects
  with sharing enabled (app is foreground then), stop it on disconnect. Check what targetSdk the
  manifest uses and the API level of the phone (Galaxy Z Fold7, SM-F966B -- query with adb) and obey
  the exact rules for that level. Research the official Android docs, don't guess.
  Then the foreground && focused gate in PhoneSharing.configure()/updateCapture() must no longer zero
  the mask; availability should depend on: connected + supported + switch enabled + permission granted.
  Clipboard sync may stay focus-gated (Android blocks background clipboard reads anyway).
  Keep the stream itself running in background only if it already does; if the video stream/socket is
  torn down on onPause/surfaceDestroyed (it is: surfaceDestroyed -> dropStream()), the sharing
  connection dies with it. Investigate and decide the minimal correct design so the TLS connection +
  sharing survive while the surface is gone (e.g. keep socket alive, pause decoding, or a lightweight
  sharing-only state). Explain the choice in the spec. Locked phone (relock() drops the stream on
  purpose for security) -- keep that security behaviour unless trivially safe; state clearly in the
  spec what happens when locked.
- Startup latency: keep ON-DEMAND capture (no always-hot mic), but cut the start-up delay hard.
  Measured today: first ~0.5-1 s+ of speech lost after a laptop app opens folddeck_mic. Contributors:
  host polls pw-dump every 0.5 s (Sharing.poll_demand), then DEMAND -> phone posts to main thread ->
  AudioRecord create/start, then 40 ms pacat latency. Ideas to evaluate: event-driven demand detection
  (pw-mon / pactl subscribe instead of 0.5 s polling), pre-creating/warming AudioRecord when the mic
  is "ready" but not recording (does that light the privacy indicator? check docs -- AudioRecord is
  only "recording" after startRecording()), starting capture off the main thread, and a short
  host-side zero-padding so the consumer sees continuous audio. Measure before/after with a real
  number (time from parec/pw-record open to first non-silent PCM from phone) and report it.
- Gain: phone PCM arrived at ~5% full scale. Host now sets folddeck_mic to 250% (my commit 9ecdc42).
  Better: fix at source if possible (e.g. AudioSource choice VOICE_RECOGNITION vs MIC/VOICE_COMMUNICATION
  on Samsung, or apply gain on phone). Pick what gives normal speech levels without clipping; keep
  the 250% host fallback only if still needed.

## Root causes already found (evidence, 2026-11-03)
- Phone sent CONFIG mask 000 for many revisions: PhoneSharing gates mask on foreground && focused.
  On the Fold, when another window held focus (Settings in split/other display), the mask was 0 even
  while streaming. Bringing FoldDeck to front -> mask 111 -> host created folddeck_mic and set it as
  default (pw-metadata default.configured.audio.source). That part of the host works.
- Later the mic+camera dropped together (mask 001) with app focused and permissions granted: matches
  sharingBadge tap -> stopInputs() which persists mic/camera OFF. Requirement 3 removes this.
- Host debug logging lines in host/sharing.py ("    sharing: ...") are useful; keep them (concise).

## Environment / how to build, deploy, test
- Host: python3 host/stream.py runs as systemd user unit folddeck.service. Restart:
  systemctl --user restart folddeck   (the phone auto-reconnects in ~1 s). Logs:
  journalctl --user -u folddeck -f
- Host tests: python3 host/test_*.py (look at how they are run; keep all passing).
- Android: android/build.sh builds android/build/folddeck.apk (no Gradle). Bump versionCode/versionName
  in android/AndroidManifest.xml (current 25 / 0.12.2 -> 26 / 0.13.0).
- Phone is connected via wireless adb:  ~/Android/Sdk/platform-tools/adb -s 192.168.1.238:46703
  Install with:  adb -s ... install -r android/build/folddeck.apk
  NEVER uninstall the app or clear its data (that wipes TLS pairing + lock PIN; the user would have to
  re-pair). If install -r fails with signature mismatch, STOP and report -- do not uninstall.
  If adb is disconnected, try adb connect 192.168.1.238:46703 once; if it fails report it, the user
  will re-enable wireless debugging.
- Do not change the phone's lock/PIN, do not grant/revoke permissions except the ones this feature
  needs (pm grant for RECORD_AUDIO/CAMERA/POST_NOTIFICATIONS is fine), do not touch other apps.
- android/device-test/sharing-check.py is an existing end-to-end check (needs gst-launch-1.0); use or
  extend it if helpful.
- Laptop audio: PipeWire + pipewire-pulse. Phone mic node name: folddeck_mic. Built-in mic:
  alsa_input.pci-0000_00_1f.3.analog-stereo.

## Acceptance criteria (verify each with real command output, report numbers)
A. Connect -> within 2 s, `pactl get-default-source` == folddeck_mic, without touching the phone,
   with FoldDeck on screen AND with FoldDeck sent to background (adb shell input keyevent HOME).
B. Disconnect (stop stream / kill host) -> default source returns to the previous one.
C. Open the default source (e.g. `parec -d @DEFAULT_SOURCE@` or python sounddevice default input,
   which is what Hermes uses) -> phone PCM arrives; report measured open->first-audio latency vs
   before.
D. No sharing badge/bar exists in the UI; no code path where a single tap disables mic/camera.
E. Camera: default video source becomes folddeck_camera on connect; frames only while consumed.
F. All host tests pass; APK builds; installed on the phone with install -r; versionName 0.13.0.
G. Update README.md "Phone sharing" section to describe the new behaviour accurately.

Report at the end: what changed (files), test results, measured latencies, anything NOT done or
uncertain, and any risk. Do not claim anything you did not verify.

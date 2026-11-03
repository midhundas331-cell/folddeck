# On-device UI check

Requires an unlocked, ADB-authorized development phone with an already paired,
reachable Folddeck host. Uses framework Instrumentation; no Gradle or test library.

```bash
android/build.sh
bash android/device-test/build.sh
adb install -r android/build/folddeck.apk
adb install -r android/build/ui-test/ui-test.apk
adb shell am instrument -w -r -e record true -e rotation 0 \
  io.folddeck.uitest/io.folddeck.uitest.UiSmoke
```

Use `-s SERIAL` on ADB commands when multiple transports are connected. Rotation
`0` tests upright; `1` tests sideways on the Fold. Omit it to use the current
orientation. `record true` temporarily allows capture inside the instrumented
process and writes screenshots to the app's external files directory. Production
still starts with `FLAG_SECURE`; there is no exported unlock/debug hook.

The check connects the real stream, exercises the local Resize and App buttons,
drags every size slider, validates geometry and key-label fit, checks Done/Back/
Reset and the split keyboard, then disconnects. It reports editor-frame UI duration
percentiles through `FrameMetrics` (not end-to-end streaming latency or a guarantee
of sustained 120 fps). Test preferences are backed up before mutation and restored
on completion, or recovered at the next run after an interrupted test. Rotation is
unfrozen at the end. `-e leaveBoth true` explicitly leaves Both with automatic sizing.

It also verifies independent pad height, wheel width, button height and both Hold
sections (with an event-recording listener, so those test taps do not click laptop
apps). For an end-to-end clipboard/microphone/camera check, with the virtual camera
installed and host running (requires `gst-launch-1.0` with PipeWire and video
conversion plugins):

```bash
python3 android/device-test/sharing-check.py SERIAL PHYSICAL_DISPLAY_ID
```

This grants Android microphone/camera permissions for the test, temporarily enables
the inputs, checks clipboard transfer in both directions, reads a camera frame and
one second of microphone PCM on the laptop, and verifies cleanup. Camera capture
uses PipeWire/GStreamer with fixed 640x480 YUY2 at 10 fps, matching Cheese's
negotiation path rather than bypassing it through direct V4L2. Camera/audio
samples are checked in memory and not saved. Text clipboards are restored afterward.
The microphone and camera finish disabled; granted Android permissions remain.

For a video, add `-e display PHYSICAL_ID` to the instrumented run. Get the active
physical display ID from `adb shell dumpsys SurfaceFlinger --display-id` and check
which panel is on in `adb shell dumpsys display`. The test starts `screenrecord`
after opening Folddeck and stops it before leaving, writing only the test session
to `/sdcard/folddeck-ui-test.mp4`. Pull that file with ADB. On Fold devices, use
the inner panel when unfolded and the cover panel when folded; an inactive panel
returns `UNASSIGNED_LAYER_STACK`. Remove the test APK afterward:

```bash
adb uninstall io.folddeck.uitest
```

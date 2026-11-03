#!/usr/bin/env python3
"""Run the signed UI test and verify phone inputs at the actual laptop devices.

Usage: python3 android/device-test/sharing-check.py SERIAL [PHYSICAL_DISPLAY_ID]
Requires the paired host, installed APKs and a loaded Folddeck Phone loopback.
"""
import os
from pathlib import Path
import subprocess
import sys
import time
import select

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "host"))
import sharing
import demand

adb = str(Path.home() / "Android/Sdk/platform-tools/adb")
command = [adb, "-s", sys.argv[1], "shell", "am", "instrument", "-w", "-r",
           "-e", "sharing", "true", "-e", "rotation", "1", "-e", "leaveBoth", "true"]
if len(sys.argv) > 2:
    command += ["-e", "record", "true", "-e", "display", sys.argv[2]]
command += ["io.folddeck.uitest/io.folddeck.uitest.UiSmoke"]
old_clipboard = sharing.clipboard_read()
old_defaults = demand.snapshot()
consumers = []


def start_consumers():
    # Cheese uses PipeWire/GStreamer. Direct V4L2 capture misses negotiation bugs.
    consumers.append(subprocess.Popen(["gst-launch-1.0", "-q", "pipewiresrc", "target-object=folddeck_camera",
        "!", "video/x-raw,format=YUY2,width=640,height=480,framerate=10/1",
        "!", "videoconvert", "!", "video/x-raw,format=RGB", "!", "fdsink", "fd=1"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL))
    # No explicit microphone selection: verify the session default is consumed.
    consumers.append(subprocess.Popen(["parec", "--raw", "--format=s16le", "--rate=48000", "--channels=1"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL))


def stop_consumers():
    for consumer in consumers:
        if consumer.poll() is None:
            consumer.kill()
        consumer.wait()
        consumer.stdout.close()
    consumers.clear()


def read_media(seconds):
    received = [bytearray(), bytearray()]
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        readable = select.select([p.stdout for p in consumers], [], [], .1)[0]
        for i, p in enumerate(consumers):
            if p.stdout in readable:
                data = os.read(p.stdout.fileno(), 65536)
                assert data, "Consumer exited before media arrived"
                received[i].extend(data)
                # Keep a bounded tail rather than accumulating every video frame.
                limit = 3 * 640 * 480 * 3 if i == 0 else 48000 * 4
                if len(received[i]) > limit:
                    del received[i][:-limit]
    return received
checked = False
failed = False
try:
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    for line in process.stdout:
        print(line, end="", flush=True)
        if "FAIL" in line:
            failed = True
        if "stage=SHARING_IDLE" in line:
            if "SHARING_IDLE_AGAIN" not in line:
                for media, name in (("audio", "folddeck_mic"), ("video", "folddeck_camera")):
                    assert demand.configured(demand.snapshot(), "default.configured." + media + ".source") == {"name": name}
            start_consumers()
            continue
        if "stage=SHARING_STOPPED" in line:
            stop_consumers()
            continue
        if "stage=SHARING_LIVE" not in line:
            continue
        deadline = time.monotonic() + 2
        while sharing.clipboard_read() != "Folddeck phone clipboard test 🌍".encode():
            assert time.monotonic() < deadline, "Phone clipboard did not reach laptop"
            time.sleep(0.1)
        subprocess.run(["wl-copy", "--type", "text/plain;charset=utf-8"],
                       input="Folddeck laptop clipboard test 🌍".encode(), check=True)
        # Media stays in memory; simultaneous consumers keep both sensors live.
        picture, audio = read_media(4)
        assert len(picture) >= 640 * 480 * 3 and len(set(picture)) > 8, "Virtual camera has no image"
        assert len(audio) > 48000 and any(audio), "Virtual microphone has no PCM signal"
        print(f"Verified phone→laptop clipboard, {len(picture)} camera RGB bytes, {len(audio)} microphone PCM bytes", flush=True)
        checked = True
        stop_consumers()
    process.wait(timeout=20)
    assert checked and not failed and process.returncode == 0, "Device integration checks failed"
    sources = subprocess.check_output(["pactl", "list", "short", "sources"], text=True)
    assert "folddeck_mic" not in sources, "Microphone device leaked after stop"
    for media in ("audio", "video"):
        key = "default.configured." + media + ".source"
        assert demand.configured(old_defaults, key) == demand.configured(demand.snapshot(), key), "Default not restored"
finally:
    stop_consumers()
    if 'process' in globals() and process.poll() is None:
        for line in process.stdout:
            print(line, end="", flush=True)
        process.wait(timeout=25)
    if old_clipboard is None:
        subprocess.run(["wl-copy", "--clear"])
    else:
        subprocess.run(["wl-copy", "--type", "text/plain;charset=utf-8"], input=old_clipboard)

#!/usr/bin/env python3
"""Measure phone-input defaults and cold open->first-phone-audio latency on real devices.

Usage: python3 android/device-test/latency-check.py SERIAL [--sessions N] [--background]
                                                          [--force-focus] [--hot N]

Each session starts the SharingHold instrumentation (unlock, enable mic+camera,
connect) and reports:
  A  connect -> `pactl get-default-source` is folddeck_mic
     (--background: press HOME, check both defaults hold 3 s later)
  C  cold: open folddeck_mic the moment it appears -> first non-zero sample. The
     phone mic is idle until its first consumer, so this is the cold start even
     with an app (e.g. Hermes) that follows the default and holds it open.
     --hot N: N more opens of @DEFAULT_SOURCE@ while the mic may already be live.
  E  default video source is folddeck_camera
  B  force-stop the app -> previous default source restored
Needs both APKs installed (android/build.sh, android/device-test/build.sh).
"""
import argparse
import math
import statistics
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "host"))
import demand  # noqa: E402


def default_source():
    return subprocess.check_output(["pactl", "get-default-source"], text=True).strip()


def default_video():
    return (demand.configured(demand.snapshot(), "default.configured.video.source") or {}).get("name")


def wait_for(predicate, timeout, step=0.01):
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        if predicate():
            return time.monotonic() - start
        time.sleep(step)
    return None


def dbfs(value):
    return 20 * math.log10(value / 32768) if value > 0 else float("-inf")


def first_audio(device, seconds=3.0):
    """Seconds from opening `device` to its first non-zero sample, plus peak and RMS after it."""
    start = time.monotonic()
    proc = subprocess.Popen(["parec", "-d", device, "--raw", "--format=s16le", "--rate=48000",
                             "--channels=1", "--latency-msec=10"],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    assert proc.stdout is not None
    first, samples = None, []
    try:
        while time.monotonic() - start < seconds:
            chunk = proc.stdout.read(960)
            if not chunk:
                break
            values = memoryview(chunk).cast("h")
            if first is None and any(values):
                first = time.monotonic() - start
            if first is not None:
                samples.extend(values)
    finally:
        proc.kill()
        proc.wait()
    peak = max((abs(v) for v in samples), default=0)
    rms = math.sqrt(sum(v * v for v in samples) / len(samples)) if samples else 0
    return first, peak, rms


def describe(result):
    first, peak, rms = result
    if first is None:
        return "NO phone audio"
    return f"{first * 1000:.0f} ms (peak {dbfs(peak):.1f} dBFS, rms {dbfs(rms):.1f} dBFS)"


def session(adb, args, before, cold, hot):
    hold = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "seconds", "600",
                                   "-e", "focus", "true" if args.force_focus else "false",
                                   "io.folddeck.uitest/io.folddeck.uitest.SharingHold"],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    assert hold.stdout is not None
    try:
        for line in hold.stdout:
            if "FAIL" in line or "INSTRUMENTATION_FAILED" in line:
                sys.exit("hold failed: " + line.strip())
            if "stage=CONNECTING" in line:
                break

        def exists():
            return "folddeck_mic" in subprocess.check_output(["pactl", "list", "short", "sources"], text=True)
        if wait_for(exists, 10) is None:
            print("C  folddeck_mic never appeared")
            return
        cold.append(first_audio("folddeck_mic"))
        print(f"C  cold open -> first phone audio: {describe(cold[-1])}")
        took = wait_for(lambda: default_source() == "folddeck_mic", 10)
        print(f"A  connect -> default folddeck_mic: {'%.2f s' % took if took is not None else 'NOT REACHED'}")
        wait_for(lambda: default_video() == "folddeck_camera", 3)
        print(f"E  default video source: {default_video()}")
        if args.background:
            subprocess.run(adb + ["shell", "input", "keyevent", "HOME"], check=True)
            time.sleep(3)
            print(f"A  3 s after HOME: default source {default_source()}, video {default_video()}")
            hot.append(first_audio("@DEFAULT_SOURCE@"))
            print(f"C  background open -> first phone audio: {describe(hot[-1])}")
        for _ in range(args.hot):
            time.sleep(4)   # lets the 3 s lease lapse unless another app holds the mic
            hot.append(first_audio("@DEFAULT_SOURCE@"))
            print(f"C  re-open default -> first phone audio: {describe(hot[-1])}")
    finally:
        subprocess.run(adb + ["shell", "am", "force-stop", "io.folddeck.spike"])
        hold.kill()
    back = wait_for(lambda: default_source() == before, 15)
    print(f"B  disconnect -> default back to {before}: "
          f"{'%.2f s' % back if back is not None else 'NOT RESTORED: ' + default_source()}")


def summary(name, results):
    got = [r[0] * 1000 for r in results if r[0] is not None]
    if got:
        print(f"{name}: median {statistics.median(got):.0f} ms, min {min(got):.0f}, max {max(got):.0f} "
              f"({len(got)}/{len(results)} with audio)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("serial")
    ap.add_argument("--sessions", type=int, default=3)
    ap.add_argument("--hot", type=int, default=0)
    ap.add_argument("--background", action="store_true")
    ap.add_argument("--force-focus", action="store_true", help="baseline on builds that gate on focus")
    args = ap.parse_args()
    adb = [str(Path.home() / "Android/Sdk/platform-tools/adb"), "-s", args.serial]
    before = default_source()
    print(f"default source before: {before}")
    cold, hot = [], []
    for i in range(args.sessions):
        print(f"--- session {i + 1}")
        session(adb, args, before, cold, hot)
        time.sleep(2)
    summary("cold", cold)
    summary("hot", hot)


if __name__ == "__main__":
    main()

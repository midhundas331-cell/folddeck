#!/usr/bin/env python3
"""FoldDeck phase-0 spike: capture the X11 desktop, encode H.264, serve over TCP.

The whole point of this file is to answer one question before any real work
starts: what is the glass-to-glass latency on THIS hardware? If it's over
~100ms, the custom stack in PLAN.md isn't worth building (see PLAN.md §0).

Usage:
    ./stream.py --probe                 # which encoder works here, and how fast
    ./stream.py                         # serve on 0.0.0.0:5000
    ./stream.py --bind 127.0.0.1        # USB mode, pair with `adb reverse tcp:5000 tcp:5000`
    ./stream.py --clock                 # also open a millisecond clock to film

A fresh ffmpeg is spawned per connection, so every client starts on an IDR and
reconnects cleanly. That is also why -g 9999 is safe here.
"""
from __future__ import annotations

import argparse
import os
import re
import select
import ssl
import struct
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time

import security

# --------------------------------------------------------------------------- #
# Encoder selection
#
# Probe by actually encoding, never by parsing `ffmpeg -encoders`. On this very
# laptop the listing advertises both h264_vaapi and h264_nvenc, and both fail at
# runtime: the Intel iHD driver exposes only the low-power entrypoint (CQP only,
# no CBR) and NVENC can't load libcuda.so.1. A listing tells you what was
# compiled in, not what works.
# --------------------------------------------------------------------------- #
ENCODERS: list[tuple[str, list[str]]] = [
    (
        "vaapi-cbr",
        ["-vf", "format=nv12,hwupload", "-vaapi_device", "/dev/dri/renderD128",
         "-c:v", "h264_vaapi", "-rc_mode", "CBR", "-b:v", "{bitrate}",
         "-low_power", "1"],
    ),
    (
        # Intel Gen12 low-power entrypoint: constant-QP only. Quality floats with
        # scene complexity instead of bitrate, which is actually fine for a
        # desktop — a static screen costs almost nothing.
        "vaapi-cqp",
        ["-vf", "format=nv12,hwupload", "-vaapi_device", "/dev/dri/renderD128",
         "-c:v", "h264_vaapi", "-rc_mode", "CQP", "-qp", "26", "-low_power", "1"],
    ),
    (
        "nvenc",
        ["-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ull",
         "-rc", "cbr", "-b:v", "{bitrate}", "-zerolatency", "1"],
    ),
    (
        "x264",
        ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
         "-x264-params", "intra-refresh=1:sliced-threads=1:sync-lookahead=0:rc-lookahead=0",
         "-b:v", "{bitrate}", "-maxrate", "{bitrate}", "-bufsize", "{bufsize}"],
    ),
]

COMMON = ["-g", "9999", "-bf", "0", "-profile:v", "main"]

# wf-recorder equivalents of the ENCODERS above. x11grab cannot see a Wayland
# session at all, so on Hyprland the capture side is wf-recorder instead of
# ffmpeg and the encoder is configured with -p key=value rather than CLI flags.
# Measured on this laptop: it negotiates DMA-BUF and hands VA-API the frames
# without a round trip through system memory, which is why no scaling is
# available here (see wayland_capture_cmd).
WF_ENCODERS: dict[str, list[str]] = {
    "vaapi-cbr": ["-c", "h264_vaapi", "-d", "/dev/dri/renderD128",
                  "-p", "rc_mode=CBR", "-p", "b={bitrate}", "-p", "low_power=1"],
    "vaapi-cqp": ["-c", "h264_vaapi", "-d", "/dev/dri/renderD128",
                  "-p", "rc_mode=CQP", "-p", "qp=26", "-p", "low_power=1"],
    "nvenc":     ["-c", "h264_nvenc", "-p", "preset=p1", "-p", "tune=ull",
                  "-p", "rc=cbr", "-p", "b={bitrate}", "-p", "zerolatency=1"],
    "x264":      ["-c", "libx264", "-p", "preset=ultrafast", "-p", "tune=zerolatency",
                  "-p", "b={bitrate}", "-p", "maxrate={bitrate}", "-p", "bufsize={bufsize}"],
}

WF_COMMON = ["-p", "g=9999", "-b", "0", "-p", "profile=main"]


def stream_source(args) -> str:
    """Human-readable name of what is being captured, for log lines."""
    return wayland_output(args.output) if is_wayland() else args.display


def is_wayland() -> bool:
    return bool(os.environ.get("WAYLAND_DISPLAY")) or \
        os.environ.get("XDG_SESSION_TYPE", "").lower() == "wayland"


def wayland_output(preferred: str = "") -> str:
    """Name of the wlroots output to capture, e.g. eDP-1."""
    if preferred:
        return preferred
    try:
        r = subprocess.run(["wf-recorder", "--list-output"],
                           capture_output=True, text=True, timeout=10)
        # "1. Name: eDP-1 Description: Chimei Innolux Corporation 0x14C9  (eDP-1)"
        m = re.search(r"Name:\s*(\S+)", r.stdout + r.stderr)
        if m:
            return m.group(1)
    except (OSError, subprocess.SubprocessError):
        pass
    return "eDP-1"


def encoder_args(name: str, bitrate_kbps: int) -> list[str]:
    args = dict(ENCODERS)[name]
    return [
        a.format(bitrate=f"{bitrate_kbps}k", bufsize=f"{bitrate_kbps // 4}k")
        for a in args
    ] + COMMON


def probe_encoder(bitrate_kbps: int, verbose: bool = True) -> str:
    """Return the name of the first encoder that actually produces bytes."""
    for name, _ in ENCODERS:
        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
            "-frames:v", "6",
            *encoder_args(name, bitrate_kbps),
            "-f", "h264", "-",
        ]
        try:
            r = subprocess.run(cmd, capture_output=True, timeout=25)
        except subprocess.TimeoutExpired:
            if verbose:
                print(f"  {name:<10} timeout")
            continue
        ok = r.returncode == 0 and len(r.stdout) > 0
        if verbose:
            detail = f"{len(r.stdout)} bytes" if ok else r.stderr.decode().strip().split("\n")[0][:70]
            print(f"  {name:<10} {'OK  ' if ok else 'fail'} {detail}")
        if ok:
            return name
    raise RuntimeError("no working H.264 encoder")


# --------------------------------------------------------------------------- #
def wayland_capture_cmd(args, encoder: str) -> list[str]:
    """wf-recorder -> Annex-B H.264 on stdout, matching the x11grab path's output.

    Two things were established by running it, not by reading the man page:

    * `-f pipe:1` rather than `-f /dev/stdout`. The latter works, but wf-recorder
      first prints "Output file exists. Overwrite? Y/n:" onto the pipe's stderr
      and waits, which is a hang waiting to happen once stdin is not a terminal.
    * No scaling. `-F scale=...` builds a *software* filter graph, and with
      DMA-BUF capture the frames are VA-API surfaces it cannot touch — it dies
      with "Failed to configure graph filter: Function not implemented". So the
      Wayland path always encodes at the output's native size.
    """
    enc = [a.format(bitrate=f"{args.bitrate}k", bufsize=f"{args.bitrate // 4}k")
           for a in WF_ENCODERS[encoder]]
    return [
        "wf-recorder",
        "--no-damage",                      # constant rate; damage-driven is bursty
        "-o", wayland_output(args.output),
        "-r", str(args.fps),
        *enc, *WF_COMMON,
        "-m", "h264", "-f", "pipe:1",
    ]


def capture_cmd(args, encoder: str) -> list[str]:
    if is_wayland():
        return wayland_capture_cmd(args, encoder)
    return [
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-f", "x11grab",
        "-framerate", str(args.fps),
        "-video_size", f"{args.width}x{args.height}",
        "-draw_mouse", "1",
        "-i", args.display,
        *(["-s", f"{args.out_width}x{args.out_height}"] if args.out_width else []),
        *encoder_args(encoder, args.bitrate),
        "-f", "h264", "-",
    ]


def encode_headroom(args, encoder: str, frames: int = 300) -> float:
    """Encoder throughput at full tilt, in ms/frame.

    Uses a synthetic *moving* source with no realtime pacing, so the number is
    the encoder's actual speed. Measuring the live desktop instead would mostly
    measure how still your screen is: an idle desktop encodes to almost nothing
    and reports a flattering figure that collapses the moment you drag a window.
    """
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", f"testsrc2=size={args.width}x{args.height}:rate={args.fps}",
        "-frames:v", str(frames),
        *encoder_args(encoder, args.bitrate),
        "-f", "null", "-",
    ]
    t0 = time.perf_counter()
    subprocess.run(cmd, capture_output=True, timeout=180)
    elapsed = time.perf_counter() - t0
    return elapsed * 1000 / frames


def live_cadence(args, encoder: str, seconds: float = 5.0) -> tuple[float, list[float]]:
    """Throughput and inter-chunk gaps on the real desktop."""
    proc = subprocess.Popen(capture_cmd(args, encoder), stdout=subprocess.PIPE, bufsize=0)
    gaps: list[float] = []
    total = 0
    t_start = time.perf_counter()
    last = t_start
    try:
        while time.perf_counter() - t_start < seconds:
            chunk = proc.stdout.read(4096)
            if not chunk:
                break
            now = time.perf_counter()
            total += len(chunk)
            if total > 65536:  # skip the startup burst (SPS/PPS + first IDR)
                gaps.append((now - last) * 1000)
            last = now
    finally:
        proc.kill()
        proc.wait()
    elapsed = time.perf_counter() - t_start
    gaps.sort()
    return (total * 8 / elapsed / 1e6), gaps


def probe_latency(args, encoder: str) -> None:
    """The host half of the budget. Not glass-to-glass — that needs the camera
    (--clock) — but it bounds how good the rest can possibly be."""
    print(f"\nencoder headroom ({encoder}, {args.width}x{args.height}, synthetic motion)...")
    ms = encode_headroom(args, encoder)
    budget = 1000 / args.fps
    print(f"  {ms:.2f} ms/frame  ->  {1000 / ms:.0f} fps sustained "
          f"({ms / budget * 100:.0f}% of the {budget:.1f} ms budget at {args.fps} fps)")
    if ms > budget:
        print("  ** encoder cannot keep up at this resolution/fps. Lower one of them. **")

    print(f"\nlive desktop cadence ({stream_source(args)})...")
    mbps, gaps = live_cadence(args, encoder)
    if not gaps:
        print("  no data — is DISPLAY correct?")
        return
    print(f"  throughput   {mbps:.1f} Mbps")
    print(f"  chunk gap    p50 {gaps[len(gaps)//2]:.2f} ms   "
          f"p99 {gaps[int(len(gaps)*0.99)]:.2f} ms   max {gaps[-1]:.2f} ms")
    print("\n  On an idle desktop the gap tracks frame pacing (~1000/fps), not encode")
    print("  time, and throughput will be near zero — that is the damage-tracking win")
    print("  showing up for free. Re-run while dragging a window for the loaded case.")


# --------------------------------------------------------------------------- #
def open_input(enabled: bool):
    """Create the virtual keyboard and pointer, or explain why we couldn't.

    Video is useful without input, so a permissions problem downgrades to
    view-only rather than refusing to start.
    """
    if not enabled:
        return None, None
    try:
        from uinput import VirtualKeyboard, VirtualPointer
        kbd = VirtualKeyboard()
        ptr = VirtualPointer()
        print("virtual keyboard + pointer: ready (/dev/uinput)")
        return kbd, ptr
    except PermissionError:
        print("input: PERMISSION DENIED on /dev/uinput -- streaming video only.\n"
              "  Fix: sudo cp 99-folddeck.rules /etc/udev/rules.d/ && "
              "sudo udevadm control --reload-rules\n"
              "       sudo usermod -aG input $USER   (then log out and back in)")
    except FileNotFoundError:
        print("input: /dev/uinput missing -- streaming video only.\n"
              "  Fix: sudo modprobe uinput")
    except Exception as exc:  # noqa: BLE001
        print(f"input: unavailable ({exc}) -- streaming video only")
    return None, None


def serve(args, encoder: str) -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.bind, args.port))
    srv.listen(1)

    kbd, ptr = open_input(not args.no_input)

    tls = None
    if not args.no_tls:
        tls = security.server_context()
        paired = security.load_authorized()
        print(f"TLS: on   cert sha256 {security.cert_fingerprint()[:32]}...")
        print(f"paired devices: {len(paired)}"
              + ("  (next device to connect will be trusted)" if not paired else ""))
    else:
        print("TLS: OFF -- traffic is in the clear and anyone who can reach this "
              "port can drive the laptop")

    print(f"\nserving {args.out_width or args.width}x{args.out_height or args.height}"
          f"@{args.fps} via {encoder} on {args.bind}:{args.port}")
    print("waiting for the Fold...\n")

    try:
        while True:
            raw, addr = srv.accept()
            # Nagle would coalesce small tail-of-frame writes into 40ms clumps,
            # which is a whole frame of latency added for no bandwidth benefit.
            raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn = handshake(raw, addr, tls, allow_new=not args.no_pairing)
            if conn is None:
                continue

            print(f"[{time.strftime('%H:%M:%S')}] client {addr[0]}:{addr[1]} connected")
            pump(conn, args, encoder, kbd, ptr)
            print(f"[{time.strftime('%H:%M:%S')}] client gone\n")
    finally:
        for dev in (kbd, ptr):
            if dev is not None:
                dev.close()


def handshake(raw: socket.socket, addr, tls, allow_new: bool):
    """TLS-wrap the socket and authorise the client, or close it and return None.

    Both steps get a short timeout. Without one, a connection that opens and then
    says nothing pins the single-client accept loop forever, which is a trivial
    denial of service against an app whose whole job is to be available.
    """
    try:
        raw.settimeout(10)
        conn = tls.wrap_socket(raw, server_side=True) if tls else raw

        if tls is None:
            conn.settimeout(None)
            return conn

        blob = b""
        while len(blob) < security.HELLO_LEN:
            chunk = conn.recv(security.HELLO_LEN - len(blob))
            if not chunk:
                raise ConnectionError("closed during hello")
            blob += chunk

        ok, token, reason = security.check_hello(blob, allow_new)
        if not ok:
            global _reject_count, _reject_token
            if token != _reject_token:
                _reject_token, _reject_count = token, 0
            _reject_count += 1
            # A rejected client retries every second, so log the full explanation
            # once and then go quiet. Two hundred identical lines helps nobody,
            # and it buried the one line that says how to fix it.
            if _reject_count == 1:
                print(f"[{time.strftime('%H:%M:%S')}] REJECTED {addr[0]}: {reason}"
                      f" ({token[:16]}...)")
                print("    This is a different device than the paired one. If it is "
                      "yours (reinstalled the\n"
                      "    app, or cleared its data), unpair and let it claim the "
                      "slot again:\n"
                      f"        python3 {os.path.join(os.path.dirname(os.path.abspath(__file__)), 'security.py')} forget\n"
                      "        systemctl --user restart folddeck", flush=True)
            elif _reject_count % 60 == 0:
                print(f"[{time.strftime('%H:%M:%S')}] still rejecting {addr[0]} "
                      f"({_reject_count} attempts)", flush=True)
            conn.close()
            return None
        _reject_count, _reject_token = 0, ""
        if "NEW DEVICE" in reason:
            print(f"[{time.strftime('%H:%M:%S')}] {reason}: {token[:16]}... from {addr[0]}")

        conn.settimeout(None)
        return conn

    except (ssl.SSLError, socket.timeout, OSError, ConnectionError) as exc:
        print(f"[{time.strftime('%H:%M:%S')}] handshake failed from {addr[0]}: "
              f"{type(exc).__name__}: {exc}")
        try:
            raw.close()
        except OSError:
            pass
        return None


# Rejection log de-duplication: a refused client retries once a second.
_reject_count = 0
_reject_token = ""

MSG_KEY, MSG_PTR_ABS, MSG_BUTTON, MSG_SCROLL = 1, 2, 3, 4

# type -> payload length. Every message is fixed-size for its type, so no length
# field is needed; an unknown type means the stream has desynced.
_PAYLOAD_LEN = {MSG_KEY: 3, MSG_PTR_ABS: 4, MSG_BUTTON: 2, MSG_SCROLL: 4}


def input_reader(conn: socket.socket, kbd, ptr, stop: threading.Event,
                 io_lock: threading.Lock) -> None:
    """Read input events off the same socket and inject them.

    TCP is full-duplex, so the reverse channel costs no extra connection. Input
    rides the reliable stream on purpose: a dropped KEY_UP leaves a modifier
    stuck down on the laptop, which is the most user-visible failure this app
    can have and is worth far more than saving a millisecond.

    The lock matters once TLS is in play. An SSLSocket is a single OpenSSL
    object, and OpenSSL requires that only one thread touch it at a time -- the
    video thread writing while this one reads is undefined behaviour, and shows
    up as intermittent SSLError or corrupted records rather than an honest
    crash. Plain TCP was safe here because the kernel serialises for us.

    select() outside the lock keeps the wait cheap: we only take the lock once
    there is actually something to read, so a keystroke is not sitting behind a
    poll interval.
    """
    buf = b""
    try:
        while not stop.is_set():
            # SSL buffers whole records, so data can be pending in userspace
            # with nothing left for select() to see on the fd.
            pending = getattr(conn, "pending", lambda: 0)()
            if not pending:
                ready, _, _ = select.select([conn], [], [], 0.2)
                if not ready:
                    continue

            with io_lock:
                data = conn.recv(1024)
            if not data:
                break
            buf += data
            while buf:
                n = _PAYLOAD_LEN.get(buf[0])
                if n is None:
                    # Desynced: every later parse would be garbage, so drop the
                    # connection rather than inject random scancodes and clicks
                    # into the desktop.
                    print(f"    ! bad input message type {buf[0]}, closing", flush=True)
                    return
                if len(buf) < 1 + n:
                    break  # partial message; wait for the rest
                typ, payload, buf = buf[0], buf[1:1 + n], buf[1 + n:]

                if typ == MSG_KEY and kbd is not None:
                    code, down = struct.unpack(">HB", payload)
                    kbd.key(code, down == 1)
                elif typ == MSG_PTR_ABS and ptr is not None:
                    x, y = struct.unpack(">HH", payload)
                    ptr.move(x, y)
                elif typ == MSG_BUTTON and ptr is not None:
                    btn, down = struct.unpack(">BB", payload)
                    ptr.button(btn, down == 1)
                elif typ == MSG_SCROLL and ptr is not None:
                    dv, dh = struct.unpack(">hh", payload)
                    ptr.scroll(dv, dh)
    except (OSError, ConnectionResetError, ssl.SSLError, ValueError):
        pass
    finally:
        # Never leave a modifier or a mouse button held down after the phone
        # goes away; a stuck Ctrl or left-button looks like a broken laptop.
        if kbd is not None:
            kbd.release_all()
        if ptr is not None:
            ptr.release_all()


# --------------------------------------------------------------------------- #
# Desktop theme
#
# The phone paints itself in the laptop's Omarchy palette. The palette rides the
# video stream as an H.264 SEI NAL (user_data_unregistered), so there is no new
# message type and no framing change: the phone picks it out by UUID before the
# decoder sees it, and a decoder that did see it would ignore it anyway.
# `omarchy theme set` rewrites colors.toml, which pump() notices within a second.
# --------------------------------------------------------------------------- #
THEME_COLORS = os.path.expanduser("~/.local/state/omarchy/current/theme/colors.toml")
THEME_UUID = b"FOLDDECK-THEME-1"   # 16 bytes, no zeros


def theme_nal(path: str = THEME_COLORS) -> bytes:
    """The palette as a start-code-prefixed SEI NAL, or b"" with no theme."""
    try:
        with open(path, encoding="utf-8") as f:
            pairs = re.findall(r'^\s*(\w+)\s*=\s*"(#[0-9A-Fa-f]{6})"', f.read(), re.M)
    except OSError:
        return b""
    if not pairs:
        return b""
    # ASCII "key=#rrggbb" lines hold no 00 00 pair, so no emulation-prevention
    # bytes are needed and the phone can read the payload as-is.
    payload = THEME_UUID + "".join(f"{k}={v}\n" for k, v in pairs).encode()
    size = b"\xff" * (len(payload) // 255) + bytes([len(payload) % 255])
    return b"\x00\x00\x00\x01\x06\x05" + size + payload + b"\x80"


def splice_nal(chunk: bytes, nal: bytes, first: bool) -> bytes | None:
    """Insert a whole NAL before the chunk's first start code, or None if it has none.

    Only at a start code: dropped mid-NAL it would corrupt the frame around it.
    Before a 4-byte start code this leaves one 00 behind on the previous NAL,
    which Annex B allows as trailing_zero_8bits. The first chunk of a capture
    starts on a start code, so it takes the NAL at 0.
    """
    at = 0 if first else chunk.find(b"\x00\x00\x01")
    return None if at < 0 else chunk[:at] + nal + chunk[at:]


def pump(conn: socket.socket, args, encoder: str, kbd, ptr) -> None:
    proc = subprocess.Popen(capture_cmd(args, encoder), stdout=subprocess.PIPE, bufsize=0)
    total, t0, last_report = 0, time.perf_counter(), time.perf_counter()

    # Sent before the first video byte, then again whenever the theme changes.
    theme = theme_nal()
    pending_theme, last_theme_check = theme, t0

    stop = threading.Event()
    io_lock = threading.Lock()
    reader = threading.Thread(target=input_reader, args=(conn, kbd, ptr, stop, io_lock),
                              name="input", daemon=True)
    reader.start()

    try:
        while True:
            chunk = proc.stdout.read(32768)
            if not chunk:
                break

            now = time.perf_counter()
            if now - last_theme_check >= 1.0:
                last_theme_check = now
                latest = theme_nal()
                if latest and latest != theme:
                    theme = pending_theme = latest
                    print("    theme changed, sending palette", flush=True)
            if pending_theme:
                spliced = splice_nal(chunk, pending_theme, first=total == 0)
                if spliced is not None:
                    chunk, pending_theme = spliced, b""

            with io_lock:
                conn.sendall(chunk)
            total += len(chunk)

            now = time.perf_counter()
            if now - last_report >= 2.0:
                mbps = total * 8 / (now - t0) / 1e6
                print(f"    {mbps:5.1f} Mbps   {total / 1e6:6.1f} MB sent", flush=True)
                last_report = now
    except (BrokenPipeError, ConnectionResetError, ssl.SSLError, OSError) as exc:
        # A phone going away looks like SSLZeroReturnError (clean close_notify) or
        # SSLEOFError (radio dropped, socket just died). Both are routine. Letting
        # either escape killed the whole server on every disconnect, which systemd
        # then papered over by restarting it.
        if not isinstance(exc, (ssl.SSLZeroReturnError, ssl.SSLEOFError)):
            print(f"    connection ended: {type(exc).__name__}: {exc}", flush=True)
    finally:
        stop.set()
        proc.kill()
        proc.wait()
        conn.close()
        reader.join(timeout=1)


def open_clock(display: str) -> subprocess.Popen | None:
    """A millisecond clock to film alongside the phone.

    Point a 240fps camera at both screens, then subtract: the difference between
    the clock on the laptop and the clock visible in the mirrored image IS the
    glass-to-glass latency. No instrumentation, no trust in either end's timers.
    """
    env = {**os.environ, "DISPLAY": display}
    gtk_clock = os.path.join(os.path.dirname(os.path.abspath(__file__)), "clock.py")

    # GTK first: it repaints faster than the panel refreshes and carries the
    # frame-boundary bar. The terminal fallbacks do neither well.
    if os.path.exists(gtk_clock):
        try:
            subprocess.run([sys.executable, "-c", "import gi"], check=True,
                           capture_output=True, timeout=10)
            return subprocess.Popen([sys.executable, gtk_clock], env=env)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError):
            pass

    loop = "while :; do printf '\\r%s' \"$(date +%H:%M:%S.%3N)\"; done"
    if shutil.which("xterm"):
        return subprocess.Popen(
            ["xterm", "-fa", "Monospace", "-fs", "72", "-bg", "black", "-fg", "white",
             "-geometry", "20x2+40+40", "-T", "folddeck-clock",
             "-e", "bash", "-c", loop],
            env=env)
    if shutil.which("gnome-terminal"):
        return subprocess.Popen(
            ["gnome-terminal", "--title=folddeck-clock", "--", "bash", "-c", loop],
            env=env)

    print("note: no way to open a clock window (need python3-gi, xterm or "
          "gnome-terminal) — you can still stream, but not measure latency")
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bind", default="0.0.0.0",
                    help="0.0.0.0 for Wi-Fi, 127.0.0.1 for USB/adb-reverse")
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--display", default=os.environ.get("DISPLAY") or ":0.0",
                    help="X11 only; ignored on Wayland")
    ap.add_argument("--output", default="",
                    help="Wayland only: wlroots output to capture (default: first, e.g. eDP-1)")
    ap.add_argument("--width", type=int, default=1920, help="source capture width")
    ap.add_argument("--height", type=int, default=1080)
    ap.add_argument("--out-width", type=int, default=0,
                    help="encode width (0 = same as source); set to the Fold pane size")
    ap.add_argument("--out-height", type=int, default=0)
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--bitrate", type=int, default=12000, help="kbps")
    ap.add_argument("--encoder", default="", help="force one of: " + ", ".join(n for n, _ in ENCODERS))
    ap.add_argument("--no-input", action="store_true",
                    help="video only; don't create the virtual keyboard")
    ap.add_argument("--no-tls", action="store_true",
                    help="disable TLS (debugging only -- traffic goes out in the clear)")
    ap.add_argument("--no-pairing", action="store_true",
                    help="refuse unknown devices instead of trusting the first one")
    ap.add_argument("--probe", action="store_true", help="probe encoders + measure, then exit")
    ap.add_argument("--clock", action="store_true", help="open a ms clock window to film")
    args = ap.parse_args()

    if not shutil.which("ffmpeg"):
        print("ffmpeg not found: sudo pacman -S ffmpeg", file=sys.stderr)
        return 1

    if is_wayland():
        # x11grab is blind to a Wayland session, so capture goes through
        # wf-recorder instead. Everything downstream is unchanged: it still
        # receives Annex-B H.264 on a pipe.
        if not shutil.which("wf-recorder"):
            print("wf-recorder not found: sudo pacman -S wf-recorder", file=sys.stderr)
            return 1
        if args.out_width or args.out_height:
            print("note: --out-width/--out-height are ignored on Wayland; DMA-BUF "
                  "frames cannot be run through a software scaler.", file=sys.stderr)
    else:
        # An SSH/tmux shell has no DISPLAY or XAUTHORITY of its own. Borrow the
        # desktop session's, or x11grab fails with a useless error.
        os.environ.setdefault("XAUTHORITY", os.path.expanduser("~/.Xauthority"))
        os.environ["DISPLAY"] = args.display

    print("probing encoders (running each for real — the -encoders listing lies):")
    encoder = args.encoder or probe_encoder(args.bitrate)
    print(f"\n=> using {encoder}")

    if args.probe:
        probe_latency(args, encoder)
        return 0

    clock = open_clock(args.display) if args.clock else None
    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
    try:
        serve(args, encoder)
    except KeyboardInterrupt:
        pass
    finally:
        if clock:
            clock.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())

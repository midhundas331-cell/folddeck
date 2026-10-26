#!/usr/bin/env python3
"""FoldDeck host: capture the desktop, encode H.264, serve it to the phone over TLS,
and inject the phone's keyboard, pointer and scroll back through uinput.

Capture is KMS (direct framebuffer) when the privileged ffmpeg copy exists,
else wf-recorder on Wayland, else x11grab.

Usage:
    ./stream.py                         # serve on 0.0.0.0:5000
    ./stream.py --bind 127.0.0.1        # USB mode, pair with `adb reverse tcp:5000 tcp:5000`

A fresh encoder is spawned per connection, so every client starts on an IDR.
While connected, another IDR is emitted every two seconds so a damaged/dropped
decode unit recovers instead of freezing for the lifetime of the connection.
"""
from __future__ import annotations

import argparse
import copy
import glob
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

import audio
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
# Constant-QP quality for the VA-API paths (lower = sharper, more data). 26 kept
# text crisp but turned video soft and blocky; 20 is roughly 2-3x the bits
# (~25-30 Mbps with video playing). The phone says when it is on mobile data
# (MSG_NETWORK) and gets 26 there, so an hour of video is not ~12 GB of its plan.
QP_WIFI, QP_MOBILE = 20, 26
QP = QP_WIFI

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
         "-c:v", "h264_vaapi", "-rc_mode", "CQP", "-qp", "{qp}", "-low_power", "1"],
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

COMMON = ["-bf", "0", "-profile:v", "main"]

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
                  "-p", "rc_mode=CQP", "-p", "qp={qp}", "-p", "low_power=1"],
    "nvenc":     ["-c", "h264_nvenc", "-p", "preset=p1", "-p", "tune=ull",
                  "-p", "rc=cbr", "-p", "b={bitrate}", "-p", "zerolatency=1"],
    "x264":      ["-c", "libx264", "-p", "preset=ultrafast", "-p", "tune=zerolatency",
                  "-p", "b={bitrate}", "-p", "maxrate={bitrate}", "-p", "bufsize={bufsize}"],
}

WF_COMMON = ["-b", "0", "-p", "profile=main"]


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


def encoder_args(name: str, bitrate_kbps: int, gop: int = 9999, qp: int = QP) -> list[str]:
    args = dict(ENCODERS)[name]
    return [
        a.format(bitrate=f"{bitrate_kbps}k", bufsize=f"{bitrate_kbps // 4}k", qp=qp)
        for a in args
    ] + ["-g", str(gop)] + COMMON


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
    enc = [a.format(bitrate=f"{args.bitrate}k", bufsize=f"{args.bitrate // 4}k",
                    qp=getattr(args, "qp", QP))
           for a in WF_ENCODERS[encoder]]
    return [
        "wf-recorder",
        "--no-damage",
        "-o", wayland_output(args.output),
        # No -r. It pads the stream to constant rate with copies of the last
        # frame, and on a still screen (Hyprland renders ~10 fps then) those
        # copies are encoded in bursts of 5-7 at 3 ms each -- a fresh frame
        # waited behind them. Measured 2026-10-26: arrival lagged capture by
        # 20-160 ms. Without it, each real frame is encoded the moment it exists.
        *enc, "-p", f"g={max(1, args.fps * 2)}", *WF_COMMON,
        # FLV only for its framing; see flv_frames().
        "-m", "flv", "-f", "pipe:1",
    ]


# --------------------------------------------------------------------------- #
# KMS capture
#
# wf-recorder asks Hyprland for every frame, and Hyprland answers by rendering
# the whole screen a second time on its single main thread. Measured 2026-10-26
# on this laptop: capture took Hyprland from 33% to 94% CPU on a still screen,
# and a 60 fps video came through at ~9 fps. kmsgrab reads the framebuffer the
# display is already scanning out, straight from DRM, so the compositor does no
# extra work and the stream runs at a true 60. The catch is CAP_SYS_ADMIN: a
# private copy of ffmpeg holds it (see install-kms.sh). Without it, wf-recorder.
#
# kmsgrab sees only the primary plane, so Hyprland must draw the cursor into it
# (cursor:no_hardware_cursors) or the pointer vanishes from the stream.
# --------------------------------------------------------------------------- #
KMS_FFMPEG = os.path.expanduser("~/.local/lib/folddeck/ffmpeg-kms")


def kms_device() -> str:
    cards = sorted(glob.glob("/dev/dri/card*"))
    return cards[0] if cards else "/dev/dri/card0"


def kms_capture_cmd(args, frames: int = 0) -> list[str]:
    return [
        KMS_FFMPEG, "-hide_banner", "-loglevel", "error",
        "-framerate", str(args.fps), "-device", kms_device(), "-f", "kmsgrab", "-i", "-",
        # The grabbed framebuffer is a GPU buffer; map it into VA-API and convert
        # there, so no frame ever crosses into system memory.
        "-vf", "hwmap=derive_device=vaapi,scale_vaapi=format=nv12",
        "-c:v", "h264_vaapi", "-rc_mode", "CQP", "-qp", str(getattr(args, "qp", QP)), "-low_power", "1",
        "-g", str(max(1, args.fps * 2)), "-bf", "0", "-profile:v", "main",
        *(["-frames:v", str(frames)] if frames else []),
        "-flush_packets", "1", "-f", "flv", "-",
    ]


def kms_works(args) -> bool:
    """True if the privileged ffmpeg copy can actually grab and encode a frame."""
    if not os.access(KMS_FFMPEG, os.X_OK):
        return False
    try:
        r = subprocess.run(kms_capture_cmd(args, frames=2), capture_output=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        return False
    if r.returncode != 0 or not r.stdout.startswith(b"FLV"):
        print(f"kms capture unavailable: {r.stderr.decode(errors='replace').strip()[:160]}")
        return False
    return True


def capture_cmd(args, encoder: str) -> list[str]:
    if getattr(args, "kms", False):
        return kms_capture_cmd(args)
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
        *encoder_args(encoder, args.bitrate, max(1, args.fps * 2), getattr(args, "qp", QP)),
        "-f", "flv", "-",
    ]


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
    srv.listen(16)

    kbd, ptr = open_input(not args.no_input)
    try:
        audio.remove_stale_sink()   # a crashed run must not leave the laptop silent
    except (OSError, subprocess.SubprocessError):
        pass

    tls = None
    allow_new = False
    if not args.no_tls:
        tls = security.server_context()
        allow_new = configure_pairing(args.pairing)
        paired = security.load_authorized()
        print(f"TLS: on   cert sha256 {security.cert_fingerprint()[:32]}...")
        print(f"paired devices: {len(paired)}"
              + ("  (run with --pairing to trust one new device)" if not paired else ""))
    else:
        print("TLS: OFF -- traffic is in the clear and anyone who can reach this "
              "port can drive the laptop")

    print(f"\nserving {args.out_width or args.width}x{args.out_height or args.height}"
          f"@{args.fps} via {encoder} on {args.bind}:{args.port}")
    print("waiting for the Fold...\n")

    # Handshakes are isolated from the single active stream. A peer that opens a
    # TCP socket and stalls can consume one bounded worker, not the accept loop.
    handshake_slots = threading.BoundedSemaphore(8)
    active = threading.Lock()
    try:
        while True:
            raw, addr = srv.accept()
            # Nagle would coalesce small tail-of-frame writes into 40ms clumps,
            # which is a whole frame of latency added for no bandwidth benefit.
            raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # DSCP CS4: Wi-Fi WMM puts it in the video access category, which
            # contends for airtime ahead of best-effort traffic.
            raw.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0x80)
            # A phone that vanishes (left Wi-Fi, out of range) leaves data unacked;
            # drop it after 10 s instead of the kernel's ~15 min, so the laptop
            # gets its audio and encoder back.
            raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_USER_TIMEOUT, 10_000)
            dispatch_client(raw, addr, args=args, tls=tls,
                            allow_new=allow_new, encoder=encoder,
                            kbd=kbd, ptr=ptr, handshake_slots=handshake_slots,
                            active=active)
    finally:
        for dev in (kbd, ptr):
            if dev is not None:
                dev.close()


def configure_pairing(requested: bool) -> bool:
    """Translate --pairing into the same atomic one-use window as the CLI.

    The returned legacy allow_new value is deliberately false: keeping that
    flag true for the lifetime of the server would authorize every newcomer.
    """
    if requested:
        security.open_pairing()
    return False


_live: list[socket.socket | None] = [None]   # the connection that owns the stream, so a newcomer can kick it


def dispatch_client(raw, addr, *, args, tls, allow_new, encoder, kbd, ptr,
                    handshake_slots, active, handshake_fn=None, pump_fn=None):
    """Start one bounded handshake worker and return its Thread.

    At most one authorised client may own the encoder and uinput devices, but
    unauthorised or stalled peers are checked concurrently so they cannot pin
    the server's accept loop.
    """
    handshake_fn = handshake_fn or handshake
    pump_fn = pump_fn or pump

    if not handshake_slots.acquire(blocking=False):
        raw.close()
        return None

    def worker():
        conn = None
        owns_stream = False
        try:
            conn = handshake_fn(raw, addr, tls, allow_new)
        finally:
            handshake_slots.release()

        if conn is None:
            return
        try:
            owns_stream = active.acquire(blocking=False)
            if not owns_stream and _live[0] is not None:
                # Only an authorised phone gets here, and there is one phone: a
                # second connection means the first is dead -- typically Wi-Fi to
                # mobile data, which never closes the old socket. Kick it rather
                # than refuse the phone until TCP notices, minutes later.
                print(f"[{time.strftime('%H:%M:%S')}] {addr[0]} takes over the stream")
                try:
                    socket.socket.shutdown(_live[0], socket.SHUT_RDWR)  # raw fd, not TLS
                except OSError:
                    pass
                owns_stream = active.acquire(timeout=5)
            if not owns_stream:
                print(f"[{time.strftime('%H:%M:%S')}] authenticated client "
                      f"{addr[0]} refused: another stream is active")
                return
            _live[0] = conn
            print(f"[{time.strftime('%H:%M:%S')}] client {addr[0]}:{addr[1]} connected")
            pump_fn(conn, args, encoder, kbd, ptr)
            print(f"[{time.strftime('%H:%M:%S')}] client gone\n")
        finally:
            try:
                conn.close()
            except OSError:
                pass
            if owns_stream:
                _live[0] = None
                active.release()

    thread = threading.Thread(target=worker, name=f"folddeck-{addr[0]}", daemon=True)
    thread.start()
    return thread


def handshake(raw: socket.socket, addr, tls, allow_new: bool):
    """TLS-wrap the socket and authorise the client, or close it and return None.

    Both steps get a short timeout. Without one, a connection that opens and then
    says nothing pins the single-client accept loop forever, which is a trivial
    denial of service against an app whose whole job is to be available.
    """
    try:
        raw.settimeout(5)
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

# Type 2 was the pre-0.2 permille pointer; retired, never reuse the number.
MSG_KEY, MSG_BUTTON, MSG_SCROLL, MSG_PTR_ABS16, MSG_AUDIO = 1, 3, 4, 5, 6
MSG_NETWORK = 7

# type -> payload length. Every message is fixed-size for its type, so no length
# field is needed; an unknown type means the stream has desynced.
#
# MSG_AUDIO (u8 on/off) is the phone opting in to laptop audio; an older APK
# never sends it, so it never receives audio NALs its decoder would choke on.
# MSG_NETWORK (u8, 1 = mobile data) picks the stream quality; an older APK never
# sends it and keeps the Wi-Fi quality.
_PAYLOAD_LEN = {MSG_KEY: 3, MSG_BUTTON: 2, MSG_SCROLL: 4,
                MSG_PTR_ABS16: 4, MSG_AUDIO: 1, MSG_NETWORK: 1}


def input_reader(conn: socket.socket, kbd, ptr, stop: threading.Event,
                 io_lock: threading.Lock, on_audio=None, on_network=None) -> None:
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
                elif typ == MSG_PTR_ABS16 and ptr is not None:
                    x, y = struct.unpack(">HH", payload)
                    ptr.move_abs(x, y)
                elif typ == MSG_BUTTON and ptr is not None:
                    btn, down = struct.unpack(">BB", payload)
                    ptr.button(btn, down == 1)
                elif typ == MSG_SCROLL and ptr is not None:
                    dv, dh = struct.unpack(">hh", payload)
                    ptr.scroll(dv, dh)
                elif typ == MSG_AUDIO and on_audio is not None:
                    on_audio(payload[0] == 1)
                elif typ == MSG_NETWORK and on_network is not None:
                    on_network(payload[0] == 1)
    except (OSError, ConnectionResetError, ssl.SSLError, ValueError):
        pass
    finally:
        # Never leave a modifier or a mouse button held down after the phone
        # goes away; a stuck Ctrl or left-button looks like a broken laptop.
        if kbd is not None:
            kbd.release_all()
        if ptr is not None:
            ptr.release_all()
        # A reader that quit on its own (desync, phone closed) must end the
        # stream too, or the video plays on with input silently dead.
        if not stop.is_set():
            try:
                socket.socket.shutdown(conn, socket.SHUT_RDWR)   # raw fd, not TLS
            except (OSError, TypeError):
                pass


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


# Every unit sent to the phone -- a frame, the palette, a slice of audio -- ends
# with an access unit delimiter. A NAL in Annex-B only ends where the next start
# code begins, so without a terminator the phone held each frame until the next
# one arrived and then one more (two frames of latency). The AUD's start code
# closes the unit at once. An older APK just hands the AUD to its decoder.
AUD = b"\x00\x00\x00\x01\x09\xf0"


def _read_exact(f, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = f.read(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return bytes(buf)


def _avcc_nals(body: bytes, size_len: int) -> list[bytes]:
    out, i = [], 0
    while i + size_len <= len(body):
        n = int.from_bytes(body[i:i + size_len], "big")
        i += size_len
        out.append(body[i:i + n])
        i += n
    return out


def flv_frames(f):
    """Yield each video frame of an FLV capture as Annex-B bytes.

    FLV is here only for its framing: every tag states its length, so a frame is
    known complete the moment its last byte arrives. SPS/PPS live in the FLV
    sequence header rather than in-band, so they are put back before every
    keyframe, which is what the phone's decoder setup expects.
    """
    header = _read_exact(f, 9)
    if header[:3] != b"FLV":
        raise ValueError("capture is not FLV")
    _read_exact(f, int.from_bytes(header[5:9], "big") - 9 + 4)   # + PreviousTagSize0
    params, size_len = b"", 4
    while True:
        tag = _read_exact(f, 11)
        data = _read_exact(f, int.from_bytes(tag[1:4], "big"))
        _read_exact(f, 4)                                         # PreviousTagSize
        if tag[0] & 0x1F != 9 or len(data) < 5 or data[0] & 0x0F != 7:
            continue                                              # not AVC video
        keyframe, kind, body = data[0] >> 4 == 1, data[1], data[5:]
        if kind == 0:                                             # avcC record
            size_len = (body[4] & 3) + 1
            sets, i = [], 6
            for count_at_end in (False, True):
                count = body[5] & 0x1F if not count_at_end else body[i]
                i += count_at_end
                for _ in range(count):
                    n = int.from_bytes(body[i:i + 2], "big")
                    sets.append(body[i + 2:i + 2 + n])
                    i += 2 + n
            params = b"".join(b"\x00\x00\x00\x01" + n for n in sets)
        elif kind == 1:
            nals = _avcc_nals(body, size_len)
            yield (params if keyframe else b"") + b"".join(
                b"\x00\x00\x00\x01" + n for n in nals)


def pump(conn: socket.socket, args, encoder: str, kbd, ptr) -> None:
    args = copy.copy(args)   # quality is per connection
    args.qp = QP_WIFI
    want_qp = [args.qp]      # set by the input thread from MSG_NETWORK

    def on_network(mobile: bool) -> None:
        want_qp[0] = QP_MOBILE if mobile else QP_WIFI

    proc = subprocess.Popen(capture_cmd(args, encoder), stdout=subprocess.PIPE, bufsize=0)
    total, t0, last_report = 0, time.perf_counter(), time.perf_counter()
    frames = frames_at_report = 0

    stop = threading.Event()
    io_lock = threading.Lock()   # one thread inside OpenSSL at a time

    def send(unit: bytes) -> None:
        with io_lock:
            conn.sendall(unit + AUD)

    route = audio.AudioRoute()

    def audio_sender() -> None:
        # Audio has its own clock. Riding on video frames tied it to the screen:
        # a still desktop sends ~10 frames a second, which is 100 ms audio gaps.
        while not stop.is_set():
            pcm = route.take(0.2)
            if not pcm:
                continue
            try:
                send(audio.audio_nal(pcm))
            except (OSError, ssl.SSLError):
                return

    reader = threading.Thread(target=input_reader,
                              args=(conn, kbd, ptr, stop, io_lock, route.set, on_network),
                              name="input", daemon=True)
    reader.start()
    threading.Thread(target=audio_sender, name="audio-send", daemon=True).start()

    try:
        theme = theme_nal()
        if theme:
            send(theme)
        last_theme_check = t0
        waited = sending = 0.0
        mark = time.perf_counter()
        while True:
            for frame in flv_frames(proc.stdout):
                now = time.perf_counter()
                waited += now - mark
                if now - last_theme_check >= 1.0:
                    last_theme_check = now
                    latest = theme_nal()
                    if latest and latest != theme:
                        theme = latest
                        send(theme)
                        print("    theme changed, sending palette", flush=True)

                send(frame)
                mark = time.perf_counter()
                sending += mark - now
                total += len(frame)
                frames += 1

                if now - last_report >= 2.0:
                    mbps = total * 8 / (now - t0) / 1e6
                    fps = (frames - frames_at_report) / (now - last_report)
                    # Where the time went: waiting on the capture vs blocked sending.
                    # Blocked sending means the network or the phone is the limit.
                    print(f"    {mbps:5.1f} Mbps   {total / 1e6:6.1f} MB sent   {fps:5.1f} fps"
                          f"   capture {waited * 1000 / max(1, frames - frames_at_report):5.1f}"
                          f" ms/f  send {sending * 1000 / max(1, frames - frames_at_report):5.1f} ms/f",
                          flush=True)
                    last_report, frames_at_report = now, frames
                    waited = sending = 0.0
                if want_qp[0] != args.qp:
                    break
            else:
                break
            # The phone changed network class: restart the encoder at the new
            # quality. The next keyframe carries fresh SPS/PPS in-band.
            proc.kill()
            proc.wait()
            args.qp = want_qp[0]
            print(f"    quality: QP {args.qp} for "
                  f"{'mobile data' if args.qp == QP_MOBILE else 'Wi-Fi'}", flush=True)
            proc = subprocess.Popen(capture_cmd(args, encoder), stdout=subprocess.PIPE, bufsize=0)
    except EOFError:
        print("    capture ended", flush=True)
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
        route.stop()


def argument_parser() -> argparse.ArgumentParser:
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
    pairing = ap.add_mutually_exclusive_group()
    pairing.add_argument("--pairing", action="store_true",
                         help="temporarily trust one new device (off by default)")
    pairing.add_argument("--no-pairing", action="store_false", dest="pairing",
                         help=argparse.SUPPRESS)  # compatibility with old service files
    ap.set_defaults(pairing=False)
    return ap


def main() -> int:
    ap = argument_parser()
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

    args.kms = is_wayland() and kms_works(args)
    print("capture: " + ("KMS (direct framebuffer)" if args.kms
                         else "wf-recorder (compositor screencopy)"))

    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
    try:
        serve(args, encoder)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

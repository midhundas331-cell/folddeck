#!/usr/bin/env python3
"""Laptop audio -> phone, while the phone asks for it.

PipeWire (through its pulse shim) does the routing: a null sink named
"folddeck" becomes the default output and every playing stream is moved onto
it, so the laptop's speakers go quiet. Its monitor is recorded as raw 48 kHz
s16le stereo and handed to pump(), which splices it into the video stream as
an SEI NAL, the same way the theme rides.

On stop, the previous default sink comes back and the streams move back to it.
A null sink left over by a crashed run is removed at startup, so a crash
cannot leave the laptop silent past the next restart.
"""
from __future__ import annotations

import re
import subprocess
import threading

SINK = "folddeck"
RATE, CHANNELS = 48000, 2
BYTES_PER_SEC = RATE * CHANNELS * 2
# ponytail: raw PCM is ~1.5 Mbps on top of the video; Opus if bandwidth matters away from home.
MAX_BUFFERED = BYTES_PER_SEC // 4          # 250 ms; older audio is dropped, not queued
AUDIO_UUID = b"FOLDDECK-AUDIO-1"           # 16 bytes, no zeros


def _pactl(*args: str) -> str:
    r = subprocess.run(["pactl", *args], capture_output=True, text=True, timeout=5)
    return r.stdout.strip()


def _sink_index(name: str) -> str | None:
    for line in _pactl("list", "short", "sinks").splitlines():
        cols = line.split("\t")
        if len(cols) > 1 and cols[1] == name:
            return cols[0]
    return None


def _move_streams(src_index: str | None, dest: str) -> None:
    """Move every sink-input (on src_index, or on any sink if None) to dest."""
    for line in _pactl("list", "short", "sink-inputs").splitlines():
        cols = line.split("\t")
        if len(cols) > 1 and (src_index is None or cols[1] == src_index):
            _pactl("move-sink-input", cols[0], dest)


def remove_stale_sink() -> None:
    for line in _pactl("list", "short", "modules").splitlines():
        if "module-null-sink" in line and f"sink_name={SINK}" in line:
            _pactl("unload-module", line.split("\t")[0])


def escape(rbsp: bytes) -> bytes:
    """H.264 emulation prevention: no 00 00 0x (x <= 3) may appear inside a NAL."""
    return re.sub(rb"\x00\x00(?=[\x00-\x03])", b"\x00\x00\x03", rbsp)


def audio_nal(pcm: bytes) -> bytes:
    """PCM as a start-code-prefixed SEI NAL (user_data_unregistered)."""
    payload = AUDIO_UUID + pcm
    size = b"\xff" * (len(payload) // 255) + bytes([len(payload) % 255])
    return b"\x00\x00\x00\x01\x06" + escape(b"\x05" + size + payload) + b"\x80"


class AudioRoute:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        # The input thread turns audio on; pump()'s teardown turns it off.
        self._state = threading.RLock()
        self._buf = bytearray()
        self._proc: subprocess.Popen | None = None
        self._module: str | None = None
        self._previous: str | None = None

    def set(self, on: bool) -> None:
        with self._state:
            try:
                self.start() if on else self.stop()
            except (OSError, subprocess.SubprocessError) as exc:
                print(f"    audio: unavailable ({exc})", flush=True)
                self.stop()

    def start(self) -> None:
        if self._proc is not None:
            return
        remove_stale_sink()
        self._previous = _pactl("get-default-sink") or None
        self._module = _pactl("load-module", "module-null-sink", f"sink_name={SINK}",
                              f"rate={RATE}", f"channels={CHANNELS}",
                              "sink_properties=device.description=FoldDeck") or None
        _pactl("set-default-sink", SINK)
        _move_streams(None, SINK)
        self._proc = subprocess.Popen(
            ["parec", "-d", f"{SINK}.monitor", "--raw", "--format=s16le",
             f"--rate={RATE}", f"--channels={CHANNELS}", "--latency-msec=20"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        threading.Thread(target=self._read, args=(self._proc,), name="audio",
                         daemon=True).start()
        print(f"    audio: laptop output routed to the phone (was {self._previous})",
              flush=True)

    def _read(self, proc: subprocess.Popen) -> None:
        assert proc.stdout is not None
        while True:
            chunk = proc.stdout.read(4096)
            if not chunk:
                return
            with self._lock:
                self._buf += chunk
                if len(self._buf) > MAX_BUFFERED:
                    # Keep whole frames so the channels never swap.
                    drop = len(self._buf) - MAX_BUFFERED
                    del self._buf[:drop - drop % 4]

    def take(self) -> bytes:
        """Everything recorded since the last call, in whole frames."""
        with self._lock:
            n = len(self._buf) - len(self._buf) % 4
            data = bytes(self._buf[:n])
            del self._buf[:n]
        # Pure silence is not worth sending: the phone just runs dry, which is silence.
        return data if data.strip(b"\0") else b""

    def stop(self) -> None:
        with self._state:
            self._stop()

    def _stop(self) -> None:
        if self._proc is not None:
            self._proc.kill()
            self._proc.wait()
            self._proc = None
        if self._module is None:
            return
        try:
            if self._previous:
                _pactl("set-default-sink", self._previous)
                _move_streams(_sink_index(SINK), self._previous)
            _pactl("unload-module", self._module)
        except (OSError, subprocess.SubprocessError):
            pass
        self._module = None
        with self._lock:
            self._buf.clear()
        print(f"    audio: back on the laptop ({self._previous})", flush=True)


if __name__ == "__main__":
    # Self-check of the framing; needs no audio server.
    assert escape(b"\x00\x00\x00\x00\x01\x00\x00\x04") == \
        b"\x00\x00\x03\x00\x00\x03\x01\x00\x00\x04"
    nal = audio_nal(b"\x00" * 64 + b"\x01\x02")
    body = nal[4:]
    assert b"\x00\x00\x00" not in body and b"\x00\x00\x01" not in body
    assert b"\x00\x00\x02" not in body
    unescaped = re.sub(rb"\x00\x00\x03", b"\x00\x00", body[1:-1])
    assert unescaped[2:2 + 16] == AUDIO_UUID and unescaped.endswith(b"\x01\x02")
    print("audio framing ok")

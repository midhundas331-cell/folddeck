"""Wire check for the absolute-pointer messages, with no uinput and no X11.

Feeds input_reader() exactly the bytes H264Stream.sendPointer() writes, through
a socketpair, into a fake pointer -- so it runs anywhere, including while a live
stream.py is serving the phone.

Two things are worth pinning down. The framing table: MSG_PTR_ABS16 is a new
message type, and an unknown type makes the reader drop the connection, so a
missing entry would look like a phone that disconnects the moment you move the
mouse. And the scaling: the phone now sends 0..65535 rather than permille, and
getting that wrong parks the cursor in a corner of the desktop.

    python3 test_pointer.py
"""
from __future__ import annotations

import socket
import struct
import threading

from stream import MSG_PTR_ABS, MSG_PTR_ABS16, input_reader
from uinput import ABS_RANGE, U16_MAX


class FakePointer:
    """Records what the reader asked for, and what move_abs would emit."""

    def __init__(self):
        self.calls = []
        self.axes = []

    def move(self, x, y):
        self.calls.append(("move", x, y))
        # Mirror VirtualPointer.move: permille in, u16 through to move_abs.
        self.move_abs(x * U16_MAX // 1000, y * U16_MAX // 1000)

    def move_abs(self, x, y):
        self.calls.append(("move_abs", x, y))
        self.axes.append((max(0, min(U16_MAX, x)) * ABS_RANGE // U16_MAX,
                          max(0, min(U16_MAX, y)) * ABS_RANGE // U16_MAX))

    def button(self, btn, down):
        self.calls.append(("button", btn, down))

    def scroll(self, dv, dh=0):
        self.calls.append(("scroll", dv, dh))

    def release_all(self):
        pass


def drive(payload: bytes) -> FakePointer:
    """Run payload through input_reader and return what the pointer saw."""
    a, b = socket.socketpair()
    ptr = FakePointer()
    stop = threading.Event()
    t = threading.Thread(target=input_reader,
                         args=(b, None, ptr, stop, threading.Lock()), daemon=True)
    t.start()
    a.sendall(payload)
    a.close()
    t.join(timeout=5)
    assert not t.is_alive(), "input_reader did not finish"
    b.close()
    return ptr


def abs16(x: int, y: int) -> bytes:
    return struct.pack(">BHH", MSG_PTR_ABS16, x, y)


def main() -> int:
    # The new message, at both ends of each axis and in the middle.
    ptr = drive(abs16(0, 0) + abs16(U16_MAX, U16_MAX) + abs16(32768, 16384))
    assert ptr.calls == [("move_abs", 0, 0), ("move_abs", U16_MAX, U16_MAX),
                         ("move_abs", 32768, 16384)], ptr.calls
    assert ptr.axes[0] == (0, 0), ptr.axes[0]
    assert ptr.axes[1] == (ABS_RANGE, ABS_RANGE), ptr.axes[1]
    assert abs(ptr.axes[2][0] - ABS_RANGE // 2) <= 1, ptr.axes[2]
    assert abs(ptr.axes[2][1] - ABS_RANGE // 4) <= 1, ptr.axes[2]

    # A fragmented message still parses: the reader buffers partial frames, and
    # a 5-byte message split across TCP segments is the normal case, not a rare
    # one, once the mouse is sending a sample every few milliseconds.
    whole = abs16(12345, 54321)
    a, b = socket.socketpair()
    ptr = FakePointer()
    t = threading.Thread(target=input_reader,
                         args=(b, None, ptr, threading.Event(), threading.Lock()),
                         daemon=True)
    t.start()
    for byte in whole:
        a.sendall(bytes([byte]))
    a.close()
    t.join(timeout=5)
    b.close()
    assert ptr.calls == [("move_abs", 12345, 54321)], ptr.calls

    # The old permille message still works, so an APK built before this one
    # keeps driving a host built after it.
    ptr = drive(struct.pack(">BHH", MSG_PTR_ABS, 500, 250))
    assert ptr.calls[0] == ("move", 500, 250), ptr.calls
    assert abs(ptr.axes[0][0] - ABS_RANGE // 2) <= 2, ptr.axes[0]
    assert abs(ptr.axes[0][1] - ABS_RANGE // 4) <= 2, ptr.axes[0]

    # An unknown type is still a hard stop rather than a misparse.
    ptr = drive(bytes([99, 0, 0, 0, 0]) + abs16(1, 1))
    assert ptr.calls == [], ptr.calls

    print("test_pointer ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

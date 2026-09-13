"""End-to-end check of the reverse input channel, without needing a server.

Drives stream.py's input_reader() over a socketpair with exactly the bytes
KeyboardView produces, into a real uinput device, and confirms X11 sees the
keys arrive. Runs against its own uniquely-named keyboard so it can be run
while a live stream.py is serving the phone.

    python3 test_input.py
"""
from __future__ import annotations

import socket
import struct
import subprocess
import sys
import threading
import time

from stream import MSG_BUTTON, MSG_KEY, MSG_PTR_ABS, MSG_SCROLL, input_reader
from uinput import VirtualKeyboard, VirtualPointer

KEY_A, KEY_B, KEY_C, KEY_LEFTSHIFT, KEY_LEFTCTRL = 30, 48, 46, 42, 29
NAMES = {30: "a", 48: "b", 46: "c", 42: "LeftShift", 29: "LeftCtrl"}
# Distinct names, neither a prefix of the other: device_id() matches on substring,
# so "...Test" and "...Test Pointer" would both match the first lookup.
KBD_NAME = "FoldDeck Test Keyboard"
PTR_NAME = "FoldDeck Test Pointer"


def key_msg(code: int, down: bool) -> bytes:
    """The exact 4 bytes KeyboardView -> H264Stream.sendKey() puts on the wire."""
    return struct.pack(">BHB", MSG_KEY, code, 1 if down else 0)


def ptr_msg(x: int, y: int) -> bytes:
    return struct.pack(">BHH", MSG_PTR_ABS, x, y)


def button_msg(btn: int, down: bool) -> bytes:
    return struct.pack(">BBB", MSG_BUTTON, btn, 1 if down else 0)


def scroll_msg(dv: int, dh: int) -> bytes:
    return struct.pack(">Bhh", MSG_SCROLL, dv, dh)


def cursor_xy() -> tuple[int, int]:
    out = subprocess.run(["xdotool", "getmouselocation", "--shell"],
                         capture_output=True, text=True).stdout
    d = dict(line.split("=") for line in out.strip().split("\n") if "=" in line)
    return int(d["X"]), int(d["Y"])


def parse_xi2(out: str, dev: str) -> tuple[int, int, list[str]]:
    """Count Raw key events originating from our device.

    Only the Raw* events are counted: each physical event also surfaces as a
    cooked KeyPress on the master keyboard, so counting both would double every
    keystroke. Filtering on the source device id keeps stray input from the
    real keyboard out of the tally.
    """
    presses = releases = 0
    codes: list[str] = []
    for block in out.split("EVENT type"):
        is_press = "(RawKeyPress)" in block
        is_release = "(RawKeyRelease)" in block
        if not (is_press or is_release):
            continue
        if f"({dev})" not in block:
            continue
        presses += is_press
        releases += is_release
        for line in block.split("\n"):
            if line.strip().startswith("detail:"):
                codes.append(line.split(":")[1].strip())
                break
    return presses, releases, codes


def device_id(name: str) -> str | None:
    out = subprocess.run(["xinput", "list"], capture_output=True, text=True).stdout
    for line in out.split("\n"):
        if name in line and "id=" in line:
            return line.split("id=")[1].split()[0].strip()
    return None


def test_pointer(ptr, host_side, phone_side) -> bool:
    """Drive the pointer through input_reader and read the cursor back from X."""
    screen_w, screen_h = 1920, 1080
    before = cursor_xy()
    ok = True
    print("\npointer, via input_reader:")
    for permille in [(500, 500), (100, 900), (900, 100), (500, 500)]:
        phone_side.sendall(ptr_msg(*permille))
        time.sleep(0.3)
        x, y = cursor_xy()
        want_x = permille[0] * (screen_w - 1) // 1000
        want_y = permille[1] * (screen_h - 1) // 1000
        # A couple of pixels of slack: the ABS range maps onto screen pixels
        # through two roundings.
        good = abs(x - want_x) <= 3 and abs(y - want_y) <= 3
        ok &= good
        print(f"  {permille[0]:4d},{permille[1]:4d} permille -> cursor {x:5d},{y:5d}"
              f"   want ~{want_x},{want_y}  {'ok' if good else 'MISMATCH'}")

    print("\nscroll + buttons (counted from X, not from effects):")
    phone_side.sendall(scroll_msg(3, 0))
    phone_side.sendall(scroll_msg(-3, 0))
    phone_side.sendall(button_msg(1, True))
    time.sleep(0.05)
    phone_side.sendall(button_msg(1, False))
    time.sleep(0.4)
    subprocess.run(["xdotool", "mousemove", str(before[0]), str(before[1])])
    print(f"  sent 6 scroll notches + 1 left click; cursor restored to {before}")
    return ok


def main() -> int:
    kbd = VirtualKeyboard(name=KBD_NAME)
    ptr = VirtualPointer(name=PTR_NAME)
    try:
        dev = device_id(KBD_NAME)
        if not dev:
            print("X11 never bound the test keyboard")
            return 1
        print(f"test keyboard bound as X11 device id={dev}")

        # test-xi2, not `xinput test <id>`: XI1 reports nothing for a slave device
        # attached to a master, so it silently shows zero events even when the
        # injection is working perfectly.
        monitor = subprocess.Popen(["xinput", "test-xi2", "--root"],
                                   stdout=subprocess.PIPE, text=True, bufsize=1)
        time.sleep(0.5)

        # A socketpair stands in for the phone's TCP connection; input_reader
        # cannot tell the difference.
        host_side, phone_side = socket.socketpair()
        stop = threading.Event()
        reader = threading.Thread(target=input_reader, args=(host_side, kbd, ptr, stop),
                                  daemon=True)
        reader.start()

        sent = []

        def send(code: int, down: bool, note: str = ""):
            phone_side.sendall(key_msg(code, down))
            sent.append((code, down))
            print(f"  {'DOWN' if down else 'UP  '} {NAMES.get(code, code):<10} {note}")
            time.sleep(0.06)

        print("\nsending the sequence a, Shift+b, held c, Ctrl:")
        send(KEY_A, True); send(KEY_A, False)
        send(KEY_LEFTSHIFT, True, "latched")
        send(KEY_B, True); send(KEY_B, False)
        send(KEY_LEFTSHIFT, False, "one-shot cleared")
        send(KEY_C, True, "held")
        time.sleep(0.2)
        send(KEY_C, False)
        send(KEY_LEFTCTRL, True); send(KEY_LEFTCTRL, False)

        # Split a message across two writes: TCP does not preserve write
        # boundaries, and the reader must reassemble rather than desync.
        print("\nsplitting one message across two TCP writes:")
        msg = key_msg(KEY_A, True)
        phone_side.sendall(msg[:1]); time.sleep(0.05); phone_side.sendall(msg[1:])
        sent.append((KEY_A, True))
        time.sleep(0.1)
        phone_side.sendall(key_msg(KEY_A, False))
        sent.append((KEY_A, False))
        print("  DOWN a          (sent as 1 byte + 3 bytes)")
        print("  UP   a")

        pointer_ok = test_pointer(ptr, host_side, phone_side)

        time.sleep(0.4)
        stop.set()
        phone_side.close(); host_side.close()
        monitor.terminate()
        out = monitor.stdout.read()
        monitor.wait()

        presses, releases, codes = parse_xi2(out, dev)
        want_down = sum(1 for _, d in sent if d)
        want_up = sum(1 for _, d in sent if not d)
        print(f"\nX11 saw   {presses} press / {releases} release")
        print(f"we sent   {want_down} down  / {want_up} up")
        print(f"keycodes  {' '.join(codes)}")
        print(f"expected  {sorted({c + 8 for c, _ in sent})}  (X11 keycode = evdev + 8)")

        keys_ok = presses == want_down and releases == want_up
        print(f"\nkeyboard: {'PASS' if keys_ok else 'FAIL'}"
              "  (every key arrived, in order, across split writes)")
        print(f"pointer:  {'PASS' if pointer_ok else 'FAIL'}"
              "  (absolute positions land where asked)")
        return 0 if (keys_ok and pointer_ok) else 1
    finally:
        kbd.close()
        ptr.close()


if __name__ == "__main__":
    sys.exit(main())

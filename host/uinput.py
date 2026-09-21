#!/usr/bin/env python3
"""A virtual keyboard via /dev/uinput, with no third-party dependencies.

The main project's injector.py uses python-evdev. That isn't installed here, and
the uinput ABI is small enough that binding it directly with ctypes/struct costs
about a hundred lines and removes a build dependency (evdev is a C extension and
needs python3-dev to install). Same kernel interface either way.

The phone sends Linux evdev scancodes and this hands them to the kernel, so the
laptop's own XKB layout, compose key, dead keys and every application shortcut
behave exactly as with a real USB keyboard -- because to the kernel it is one.

No root needed: on this machine /dev/uinput already carries an ACL granting the
desktop user rw. If yours doesn't, install host/99-folddeck.rules.
"""
from __future__ import annotations

import fcntl
import os
import struct
import time

# --- ioctl plumbing (linux/ioctl.h) ---------------------------------------- #
_IOC_NRBITS, _IOC_TYPEBITS, _IOC_SIZEBITS = 8, 8, 14
_IOC_NRSHIFT = 0
_IOC_TYPESHIFT = _IOC_NRSHIFT + _IOC_NRBITS
_IOC_SIZESHIFT = _IOC_TYPESHIFT + _IOC_TYPEBITS
_IOC_DIRSHIFT = _IOC_SIZESHIFT + _IOC_SIZEBITS
_IOC_WRITE = 1


def _IOC(direction: int, typ: int, nr: int, size: int) -> int:
    return ((direction << _IOC_DIRSHIFT) | (typ << _IOC_TYPESHIFT)
            | (nr << _IOC_NRSHIFT) | (size << _IOC_SIZESHIFT))


def _IO(typ: int, nr: int) -> int:
    return _IOC(0, typ, nr, 0)


def _IOW(typ: int, nr: int, size: int) -> int:
    return _IOC(_IOC_WRITE, typ, nr, size)


UINPUT_IOCTL_BASE = ord("U")
UI_DEV_CREATE = _IO(UINPUT_IOCTL_BASE, 1)
UI_DEV_DESTROY = _IO(UINPUT_IOCTL_BASE, 2)
UI_DEV_SETUP = _IOW(UINPUT_IOCTL_BASE, 3, 92)   # sizeof(struct uinput_setup)
UI_ABS_SETUP = _IOW(UINPUT_IOCTL_BASE, 4, 28)   # sizeof(struct uinput_abs_setup)
UI_SET_EVBIT = _IOW(UINPUT_IOCTL_BASE, 100, 4)
UI_SET_KEYBIT = _IOW(UINPUT_IOCTL_BASE, 101, 4)
UI_SET_RELBIT = _IOW(UINPUT_IOCTL_BASE, 102, 4)
UI_SET_ABSBIT = _IOW(UINPUT_IOCTL_BASE, 103, 4)

EV_SYN, EV_KEY, EV_REL, EV_ABS = 0x00, 0x01, 0x02, 0x03
SYN_REPORT = 0

REL_WHEEL, REL_HWHEEL = 0x08, 0x06
REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 0x0B, 0x0C
ABS_X, ABS_Y = 0x00, 0x01
BTN_LEFT, BTN_RIGHT, BTN_MIDDLE = 0x110, 0x111, 0x112

# struct input_event { struct timeval time; __u16 type, code; __s32 value; }
# timeval is two longs, so this is 24 bytes on 64-bit.
_EVENT = struct.Struct("@qqHHi")
# struct uinput_setup { struct input_id id; char name[80]; __u32 ff_effects_max; }
_SETUP = struct.Struct("@4H80sI")
# struct uinput_abs_setup { __u16 code; struct input_absinfo absinfo; }
# input_absinfo is 6 x __s32, so the u16 is followed by 2 bytes of padding.
_ABS_SETUP = struct.Struct("@H2x6i")

KEY_MAX = 255  # everything a PC keyboard emits lives well below this
ABS_RANGE = 32767  # absolute axes are reported in 0..ABS_RANGE
U16_MAX = 65535    # ...and the phone sends them in 0..U16_MAX


class VirtualKeyboard:
    """A uinput keyboard. Use as a context manager, or call close()."""

    def __init__(self, name: str = "FoldDeck Virtual Keyboard"):
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        try:
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
            fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_SYN)
            # The capability set is fixed at creation and cannot grow later, so
            # advertise every key we could ever be asked to send.
            for code in range(1, KEY_MAX + 1):
                fcntl.ioctl(self.fd, UI_SET_KEYBIT, code)

            setup = _SETUP.pack(
                0x03,           # BUS_USB -- desktops trust USB keyboards
                0xF01D, 0x0001, 0x0001,
                name.encode()[:79],
                0,
            )
            fcntl.ioctl(self.fd, UI_DEV_SETUP, setup)
            fcntl.ioctl(self.fd, UI_DEV_CREATE)
        except Exception:
            os.close(self.fd)
            raise

        # udev needs a moment to notice the device and let X bind it; keys sent
        # immediately after create are silently dropped.
        time.sleep(0.3)
        self._down: set[int] = set()

    def _emit(self, typ: int, code: int, value: int) -> None:
        os.write(self.fd, _EVENT.pack(0, 0, typ, code, value))

    def _syn(self) -> None:
        self._emit(EV_SYN, SYN_REPORT, 0)

    def key(self, code: int, down: bool) -> None:
        if not 1 <= code <= KEY_MAX:
            return
        if down:
            self._down.add(code)
        else:
            self._down.discard(code)
        self._emit(EV_KEY, code, 1 if down else 0)
        self._syn()

    def tap(self, *codes: int) -> None:
        for c in codes:
            self._emit(EV_KEY, c, 1)
        self._syn()
        for c in reversed(codes):
            self._emit(EV_KEY, c, 0)
        self._syn()

    def release_all(self) -> None:
        """Release every held key.

        Called when a client disconnects. Without it, a phone that dies mid-chord
        leaves Ctrl or Shift latched down on the laptop, which looks exactly like
        a broken machine and is fixed only by unplugging something.
        """
        for code in list(self._down):
            self._emit(EV_KEY, code, 0)
        self._down.clear()
        self._syn()

    def close(self) -> None:
        try:
            self.release_all()
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except OSError:
            pass
        finally:
            os.close(self.fd)

    def __enter__(self) -> "VirtualKeyboard":
        return self

    def __exit__(self, *exc) -> None:
        self.close()


class VirtualPointer:
    """An absolute pointer with buttons and a scroll wheel.

    Absolute rather than relative: the phone shows the desktop directly, so
    touching a thing should put the cursor on that thing. Trackpad-style relative
    movement is done client-side -- the phone accumulates the delta and sends an
    absolute position -- which keeps this to one device. Declaring both ABS_X/Y
    and REL_X/Y on a single device makes libinput's classification ambiguous, and
    it may decide you are a tablet, a touchscreen, or nothing at all.

    The wheel is REL_WHEEL on the same device, which is what real tablets and
    touchpads do and does not confuse that classification.
    """

    def __init__(self, name: str = "FoldDeck Virtual Pointer"):
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        try:
            for ev in (EV_KEY, EV_ABS, EV_REL, EV_SYN):
                fcntl.ioctl(self.fd, UI_SET_EVBIT, ev)
            for btn in (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE):
                fcntl.ioctl(self.fd, UI_SET_KEYBIT, btn)
            for rel in (REL_WHEEL, REL_HWHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES):
                fcntl.ioctl(self.fd, UI_SET_RELBIT, rel)
            for axis in (ABS_X, ABS_Y):
                fcntl.ioctl(self.fd, UI_SET_ABSBIT, axis)
                # code, then absinfo: value, min, max, fuzz, flat, resolution.
                # fuzz and flat must be 0 -- a non-zero flat creates a dead zone
                # around the origin and the cursor sticks in the top-left.
                fcntl.ioctl(self.fd, UI_ABS_SETUP,
                            _ABS_SETUP.pack(axis, 0, 0, ABS_RANGE, 0, 0, 0))

            fcntl.ioctl(self.fd, UI_DEV_SETUP,
                        _SETUP.pack(0x03, 0xF01D, 0x0002, 0x0001, name.encode()[:79], 0))
            fcntl.ioctl(self.fd, UI_DEV_CREATE)
        except Exception:
            os.close(self.fd)
            raise

        time.sleep(0.3)
        self._buttons: set[int] = set()

    def _emit(self, typ: int, code: int, value: int) -> None:
        os.write(self.fd, _EVENT.pack(0, 0, typ, code, value))

    def _syn(self) -> None:
        self._emit(EV_SYN, SYN_REPORT, 0)

    def move(self, x_permille: int, y_permille: int) -> None:
        """Absolute position in permille. Kept for clients older than MSG_PTR_ABS16."""
        self.move_abs(x_permille * U16_MAX // 1000, y_permille * U16_MAX // 1000)

    def move_abs(self, x: int, y: int) -> None:
        """Absolute position, each axis 0..65535.

        Permille was fine for a finger and is not for a mouse: a thousand steps
        is 1.9px per step across a 1920px desktop, so the cursor moves in
        visible jumps, and a slow movement at a low pointer speed rounds down to
        no movement at all.
        """
        self._emit(EV_ABS, ABS_X, max(0, min(U16_MAX, x)) * ABS_RANGE // U16_MAX)
        self._emit(EV_ABS, ABS_Y, max(0, min(U16_MAX, y)) * ABS_RANGE // U16_MAX)
        self._syn()

    def button(self, btn: int, down: bool) -> None:
        code = {1: BTN_LEFT, 2: BTN_RIGHT, 3: BTN_MIDDLE}.get(btn)
        if code is None:
            return
        if down:
            self._buttons.add(code)
        else:
            self._buttons.discard(code)
        self._emit(EV_KEY, code, 1 if down else 0)
        self._syn()

    def scroll(self, dv: int, dh: int = 0) -> None:
        if dv:
            self._emit(EV_REL, REL_WHEEL, dv)
            self._emit(EV_REL, REL_WHEEL_HI_RES, dv * 120)
        if dh:
            self._emit(EV_REL, REL_HWHEEL, dh)
            self._emit(EV_REL, REL_HWHEEL_HI_RES, dh * 120)
        if dv or dh:
            self._syn()

    def release_all(self) -> None:
        for code in list(self._buttons):
            self._emit(EV_KEY, code, 0)
        self._buttons.clear()
        self._syn()

    def close(self) -> None:
        try:
            self.release_all()
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except OSError:
            pass
        finally:
            os.close(self.fd)

    def __enter__(self) -> "VirtualPointer":
        return self

    def __exit__(self, *exc) -> None:
        self.close()


if __name__ == "__main__":
    # Self-test: types "hi" into whatever has focus, 3 seconds from now.
    KEY_H, KEY_I = 35, 23
    print("focus a text field; typing 'hi' in 3s...")
    time.sleep(3)
    with VirtualKeyboard() as kbd:
        kbd.tap(KEY_H)
        kbd.tap(KEY_I)
    print("done")

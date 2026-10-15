#!/usr/bin/env python3
"""A stalled unauthenticated socket must not block legitimate handshakes."""
from __future__ import annotations

import threading
import time
from types import SimpleNamespace

import stream


class FakeSocket:
    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


def main() -> None:
    slow_release = threading.Event()
    fast_entered = threading.Event()
    pumped: list[str] = []

    def fake_handshake(raw, addr, tls, allow_new):
        if addr[0] == "slow":
            slow_release.wait(2)
            return None
        fast_entered.set()
        return raw

    def fake_pump(conn, args, encoder, kbd, ptr):
        pumped.append(args.name)

    handshake_slots = threading.BoundedSemaphore(2)
    active = threading.Lock()
    common = dict(tls=None, allow_new=False, encoder="fake", kbd=None, ptr=None,
                  handshake_slots=handshake_slots, active=active,
                  handshake_fn=fake_handshake, pump_fn=fake_pump)

    t1 = stream.dispatch_client(FakeSocket(), ("slow", 1),
                                args=SimpleNamespace(name="slow"), **common)
    t2 = stream.dispatch_client(FakeSocket(), ("fast", 2),
                                args=SimpleNamespace(name="fast"), **common)
    assert fast_entered.wait(0.5), "slow unauthenticated client blocked the next handshake"
    t2.join(1)
    assert pumped == ["fast"], pumped
    slow_release.set()
    t1.join(1)
    print("concurrent accept ok")


if __name__ == "__main__":
    main()

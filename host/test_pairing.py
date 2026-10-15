#!/usr/bin/env python3
"""Regression checks for secure pairing defaults and explicit pairing windows."""
from __future__ import annotations

import os
import tempfile
import threading
import time

import security
import stream


def main() -> None:
    parser = stream.argument_parser()
    assert parser.parse_args([]).pairing is False
    assert parser.parse_args(["--pairing"]).pairing is True
    assert parser.parse_args(["--no-pairing"]).pairing is False

    tmp = tempfile.mkdtemp(prefix="folddeck-pairing-")
    security.STATE_DIR = tmp
    security.AUTHORIZED_PATH = os.path.join(tmp, "authorized_clients")
    security.PAIRING_PATH = os.path.join(tmp, "pairing_until")
    security.open_pairing(seconds=120, now=1000)
    assert security.pairing_open(now=1119)
    assert not security.pairing_open(now=1121)

    token = bytes([7]) * security.TOKEN_BYTES
    security.open_pairing(seconds=120, now=2000)
    ok, _, reason = security.check_hello(
            bytes([security.HELLO_VERSION]) + token, allow_new=False, now=2001)
    assert ok and "NEW DEVICE" in reason
    assert not security.pairing_open(now=2002), "window must close after one claim"

    # Concurrent handshake workers must not claim the one-use window twice.
    security.AUTHORIZED_PATH = os.path.join(tmp, "race_authorized_clients")
    security.PAIRING_PATH = os.path.join(tmp, "race_pairing_until")
    security.open_pairing(seconds=120, now=3000)
    original_authorize = security.authorize
    start = threading.Barrier(12)
    accepted: list[bool] = []

    def slow_authorize(token_hex: str) -> None:
        time.sleep(0.04)  # widen the old check-then-append race deterministically
        original_authorize(token_hex)

    def claim(i: int) -> None:
        start.wait()
        candidate = bytes([security.HELLO_VERSION]) + bytes([i + 1]) * security.TOKEN_BYTES
        accepted.append(security.check_hello(candidate, allow_new=False, now=3001)[0])

    security.authorize = slow_authorize
    try:
        threads = [threading.Thread(target=claim, args=(i,)) for i in range(12)]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
    finally:
        security.authorize = original_authorize
    assert accepted.count(True) == 1, f"one-use window accepted {accepted.count(True)} clients"
    assert len(security.load_authorized()) == 1

    # Consume the one-use marker before persistence so a crash cannot leave the
    # window open for a second claimant after restart.
    security.AUTHORIZED_PATH = os.path.join(tmp, "crash_authorized_clients")
    security.PAIRING_PATH = os.path.join(tmp, "crash_pairing_until")
    security.open_pairing(seconds=120, now=3500)
    original_authorize = security.authorize
    security.authorize = lambda _token: (_ for _ in ()).throw(RuntimeError("disk failure"))
    try:
        candidate = bytes([security.HELLO_VERSION]) + bytes([88]) * security.TOKEN_BYTES
        try:
            security.check_hello(candidate, allow_new=False, now=3501)
            raise AssertionError("authorization failure was swallowed")
        except RuntimeError:
            pass
    finally:
        security.authorize = original_authorize
    assert not security.pairing_open(now=3502), \
            "failed authorization must consume the pairing marker fail-closed"

    security.AUTHORIZED_PATH = os.path.join(tmp, "second_authorized_clients")
    security.PAIRING_PATH = os.path.join(tmp, "second_pairing_until")
    security.authorize((bytes([99]) * security.TOKEN_BYTES).hex())
    allow_new = stream.configure_pairing(True)
    assert allow_new is False, "--pairing must use the atomic one-use window"
    second = bytes([security.HELLO_VERSION]) + bytes([100]) * security.TOKEN_BYTES
    assert security.check_hello(second, allow_new=allow_new)[0], \
            "--pairing must add a device even when another credential exists"
    assert len(security.load_authorized()) == 2

    print("pairing defaults and window ok")


if __name__ == "__main__":
    main()

"""End-to-end check of TLS + trust-on-first-use authorisation.

Runs entirely against a temporary state directory, so it never touches the real
~/.config/folddeck and cannot accidentally pair a throwaway token (which would
lock the actual phone out).

    python3 test_tls.py
"""
from __future__ import annotations

import os
import secrets
import socket
import ssl
import sys
import tempfile
import threading

import security


def use_temp_state() -> str:
    tmp = tempfile.mkdtemp(prefix="folddeck-test-")
    security.STATE_DIR = tmp
    security.CERT_PATH = os.path.join(tmp, "server.crt")
    security.KEY_PATH = os.path.join(tmp, "server.key")
    security.AUTHORIZED_PATH = os.path.join(tmp, "authorized_clients")
    return tmp


def run_server(ctx: ssl.SSLContext, port: int, results: list, allow_new: bool):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(2)
    for _ in range(2):
        raw, _ = srv.accept()
        try:
            conn = ctx.wrap_socket(raw, server_side=True)
            blob = b""
            while len(blob) < security.HELLO_LEN:
                chunk = conn.recv(security.HELLO_LEN - len(blob))
                if not chunk:
                    break
                blob += chunk
            ok, token, reason = security.check_hello(blob, allow_new)
            results.append((ok, reason))
            conn.close()
        except Exception as exc:  # noqa: BLE001
            results.append((False, f"{type(exc).__name__}: {exc}"))
    srv.close()


def client_hello(port: int, token: bytes, verify_fingerprint: str | None = None):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE          # we pin instead, like the app does
    raw = socket.create_connection(("127.0.0.1", port), timeout=5)
    conn = ctx.wrap_socket(raw)
    der = conn.getpeercert(binary_form=True)
    import hashlib
    fingerprint = hashlib.sha256(der).hexdigest()
    if verify_fingerprint is not None and fingerprint != verify_fingerprint:
        raise AssertionError("certificate fingerprint mismatch")
    conn.sendall(bytes([security.HELLO_VERSION]) + token)
    proto = conn.version()
    conn.close()
    return proto, fingerprint


def main() -> int:
    tmp = use_temp_state()
    print(f"temp state dir: {tmp}")
    security.ensure_identity()
    expected = security.cert_fingerprint()
    print(f"cert sha256:    {expected[:32]}...")

    ctx = security.server_context()
    port = 5090
    results: list = []
    t = threading.Thread(target=run_server, args=(ctx, port, results, True), daemon=True)
    t.start()

    phone = secrets.token_bytes(security.TOKEN_BYTES)
    stranger = secrets.token_bytes(security.TOKEN_BYTES)

    print("\n1. first device connects (should be trusted on first use)")
    proto, fp = client_hello(port, phone, verify_fingerprint=expected)
    print(f"   negotiated {proto}, cert pin matches: {fp == expected}")

    print("\n2. a different device connects (should be rejected)")
    client_hello(port, stranger)

    t.join(timeout=5)
    for i, (ok, reason) in enumerate(results, 1):
        print(f"   connection {i}: {'ACCEPTED' if ok else 'REJECTED'} -- {reason}")

    paired = security.load_authorized()
    ok = (
        len(results) == 2
        and results[0][0] is True
        and results[1][0] is False
        and len(paired) == 1
        and paired == {phone.hex()}
        and proto in ("TLSv1.3", "TLSv1.2")
        and fp == expected
    )
    print(f"\npaired devices after both attempts: {len(paired)} (expected 1)")
    print("\nPASS: TLS negotiated, first device pinned, stranger refused"
          if ok else "\nFAIL")

    import shutil
    shutil.rmtree(tmp, ignore_errors=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

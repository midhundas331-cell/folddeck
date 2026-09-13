#!/usr/bin/env python3
"""TLS identity and client authorisation for the FoldDeck host.

Transport is TLS 1.2+ with a self-signed server certificate that the phone pins
on first connect. We do not hand-roll a transport protocol: TLS is the thing
that has actually been attacked for thirty years, and Python ships it.

Client authorisation is trust-on-first-use. The first phone to connect has its
token recorded; after that, only that token is accepted. That means the *first*
connection is the vulnerable one -- pair on a network you trust -- but every
connection afterwards is both encrypted and authorised, and a stranger who can
reach the port gets nothing.

State lives in ~/.config/folddeck/ with 0600 permissions:
    server.crt / server.key   the host's TLS identity
    authorized_clients        one hex client token per line
"""
from __future__ import annotations

import datetime
import os
import secrets
import ssl
import stat

STATE_DIR = os.path.expanduser("~/.config/folddeck")
CERT_PATH = os.path.join(STATE_DIR, "server.crt")
KEY_PATH = os.path.join(STATE_DIR, "server.key")
AUTHORIZED_PATH = os.path.join(STATE_DIR, "authorized_clients")

TOKEN_BYTES = 32
HELLO_VERSION = 1
HELLO_LEN = 1 + TOKEN_BYTES


def _ensure_state_dir() -> None:
    os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
    os.chmod(STATE_DIR, 0o700)


def ensure_identity() -> tuple[str, str]:
    """Return (cert_path, key_path), generating a self-signed pair on first run."""
    _ensure_state_dir()
    if os.path.exists(CERT_PATH) and os.path.exists(KEY_PATH):
        return CERT_PATH, KEY_PATH

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "folddeck-host")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    fd = os.open(KEY_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ))
    with open(CERT_PATH, "wb") as fh:
        fh.write(cert.public_bytes(serialization.Encoding.PEM))
    os.chmod(CERT_PATH, 0o644)
    return CERT_PATH, KEY_PATH


def cert_fingerprint() -> str:
    """SHA-256 of the DER certificate, as the phone pins it. Shown at startup so
    it can be compared against what the app displays."""
    import hashlib

    from cryptography import x509

    with open(CERT_PATH, "rb") as fh:
        cert = x509.load_pem_x509_certificate(fh.read())
    from cryptography.hazmat.primitives import serialization
    der = cert.public_bytes(serialization.Encoding.DER)
    return hashlib.sha256(der).hexdigest()


def server_context() -> ssl.SSLContext:
    cert, key = ensure_identity()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    # TLS 1.2 floor. 1.3 is negotiated when both ends support it, which they do:
    # OpenSSL 3.0 here, and Android 12+ on the phone.
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(certfile=cert, keyfile=key)
    return ctx


# --------------------------------------------------------------------------- #
# Trust-on-first-use client authorisation
# --------------------------------------------------------------------------- #
def load_authorized() -> set[str]:
    try:
        with open(AUTHORIZED_PATH) as fh:
            return {line.strip() for line in fh if line.strip()}
    except FileNotFoundError:
        return set()


def authorize(token_hex: str) -> None:
    _ensure_state_dir()
    with open(AUTHORIZED_PATH, "a") as fh:
        fh.write(token_hex + "\n")
    os.chmod(AUTHORIZED_PATH, stat.S_IRUSR | stat.S_IWUSR)


def forget_all() -> int:
    """Drop every paired client. Next device to connect becomes the trusted one."""
    n = len(load_authorized())
    if os.path.exists(AUTHORIZED_PATH):
        os.remove(AUTHORIZED_PATH)
    return n


def check_hello(blob: bytes, allow_new: bool) -> tuple[bool, str, str]:
    """Validate the client's opening message.

    Returns (accepted, token_hex, reason).
    """
    if len(blob) != HELLO_LEN:
        return False, "", f"bad hello length {len(blob)}"
    if blob[0] != HELLO_VERSION:
        return False, "", f"unsupported hello version {blob[0]}"

    token = blob[1:].hex()
    known = load_authorized()

    if token in known:
        return True, token, "known device"
    if not known and allow_new:
        authorize(token)
        return True, token, "NEW DEVICE PAIRED (trust-on-first-use)"
    return False, token, "unknown device -- rejected"


def new_token() -> str:
    return secrets.token_hex(TOKEN_BYTES)


if __name__ == "__main__":
    import sys

    if len(sys.argv) > 1 and sys.argv[1] == "forget":
        print(f"forgot {forget_all()} paired device(s)")
    else:
        ensure_identity()
        print(f"state dir:    {STATE_DIR}")
        print(f"cert sha256:  {cert_fingerprint()}")
        clients = load_authorized()
        print(f"paired:       {len(clients)} device(s)")
        for c in clients:
            print(f"  {c[:16]}...")

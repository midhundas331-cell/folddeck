#!/usr/bin/env python3
"""Encoder recovery settings stay bounded for interactive streaming."""
from __future__ import annotations

from types import SimpleNamespace
import stream


def main() -> None:
    args = SimpleNamespace(bitrate=12000, fps=60, output="")
    old_wayland = stream.is_wayland
    old_output = stream.wayland_output
    try:
        stream.is_wayland = lambda: True
        stream.wayland_output = lambda preferred="": "eDP-1"
        cmd = stream.wayland_capture_cmd(args, "vaapi-cqp")
    finally:
        stream.is_wayland = old_wayland
        stream.wayland_output = old_output
    assert "g=120" in cmd, cmd
    assert "g=9999" not in cmd, cmd
    print("encoder recovery ok")

    # Quality follows the connection's QP on every capture path.
    assert f"qp={stream.QP_WIFI}" in cmd, cmd
    args.qp = stream.QP_MOBILE
    try:
        stream.is_wayland = lambda: True
        stream.wayland_output = lambda preferred="": "eDP-1"
        assert f"qp={stream.QP_MOBILE}" in stream.wayland_capture_cmd(args, "vaapi-cqp")
    finally:
        stream.is_wayland = old_wayland
        stream.wayland_output = old_output
    kms = stream.kms_capture_cmd(args)
    assert kms[kms.index("-qp") + 1] == str(stream.QP_MOBILE), kms
    enc = stream.encoder_args("vaapi-cqp", 12000, qp=stream.QP_MOBILE)
    assert enc[enc.index("-qp") + 1] == str(stream.QP_MOBILE), enc
    print("quality per connection ok")


if __name__ == "__main__":
    main()

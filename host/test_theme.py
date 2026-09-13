"""Theme SEI round trip: splice the palette into a real H.264 capture the way
pump() does, split it the way H264Stream.drainNals() does, and check that the
palette comes out intact and every access unit is byte-identical to the stream
without it.

    python3 test_theme.py
"""
import os
import subprocess
import tempfile

from stream import THEME_UUID, splice_nal, theme_nal


def nals(data: bytes):
    """H264Stream.drainNals(): split on 00 00 01, strip trailing zeros."""
    out, i = [], data.find(b"\x00\x00\x01")
    while i >= 0:
        j = data.find(b"\x00\x00\x01", i + 3)
        end = len(data) if j < 0 else j
        payload = data[i + 3:end].rstrip(b"\x00") if j >= 0 else data[i + 3:end]
        if payload:
            out.append(payload)
        i = j
    return out


def theme_from(nal: bytes):
    """H264Stream.themeFrom()."""
    if len(nal) < 2 or nal[0] & 0x1F != 6 or nal[1] != 5:
        return None
    i = 2
    size = 0
    while nal[i] == 0xFF:
        size += 255
        i += 1
    size += nal[i]
    i += 1
    body = nal[i:i + size]
    return body[16:].decode("ascii") if body[:16] == THEME_UUID else None


with tempfile.TemporaryDirectory() as tmp:
    colors = os.path.join(tmp, "colors.toml")
    # Long enough that payloadSize needs the 0xFF continuation byte.
    lines = [f'color{n} = "#{n:02x}{n:02x}{n:02x}"' for n in range(1, 40)]
    with open(colors, "w") as f:
        f.write('# comment\naccent = "#3CBF5C"\nhyprland_active_border = "rgba(3CBF5Cbb)"\n'
                + "\n".join(lines) + "\n")
    nal = theme_nal(colors)
    assert theme_nal(os.path.join(tmp, "missing.toml")) == b""

video = subprocess.run(
    ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
     "-i", "testsrc=size=640x360:rate=30", "-frames:v", "90",
     "-c:v", "libx264", "-g", "30", "-bf", "0", "-f", "h264", "-"],
    capture_output=True, check=True).stdout

chunks = [video[i:i + 4096] for i in range(0, len(video), 4096)]
assert len(chunks) > 3, "capture too small to splice mid-stream"
pending, sent = [nal, None, None, nal], []
for n, chunk in enumerate(chunks):
    if n < len(pending) and pending[n]:
        chunk = splice_nal(chunk, pending[n], first=n == 0)
        assert chunk is not None
    sent.append(chunk)
stream = b"".join(sent)

got = nals(stream)
palettes = [p for p in map(theme_from, got) if p is not None]
video_nals = [n for n in got if theme_from(n) is None]

assert len(palettes) == 2, palettes
assert palettes[0].startswith("accent=#3CBF5C\ncolor1=#010101\n"), palettes[0]
assert "rgba" not in palettes[0] and len(palettes[0]) + 16 > 255
assert b"\x00\x00" not in nal[4:], "payload would need emulation prevention"
assert video_nals == nals(video), "splice changed the video"
print(f"ok: {len(video_nals)} video NALs unchanged, palette of "
      f"{palettes[0].count(chr(10))} colours delivered twice")

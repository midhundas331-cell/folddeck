"""Wire round trip: convert a real FLV capture the way pump() does (flv_frames,
palette between frames, an AUD after every unit), split it the way the phone's
AnnexB splitter does, and check the palette comes out intact and the video NALs
are byte-identical to ffmpeg's own Annex-B output for the same encode.

    python3 test_theme.py
"""
import os
import subprocess
import tempfile

import io

from stream import AUD, THEME_UUID, flv_frames, theme_nal


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

def encode(fmt: str) -> bytes:
    return subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
         "-i", "testsrc=size=640x360:rate=30", "-frames:v", "90",
         "-c:v", "libx264", "-g", "30", "-bf", "0", "-f", fmt, "-"],
        capture_output=True, check=True).stdout


video = encode("h264")
frames = []
try:
    for frame in flv_frames(io.BytesIO(encode("flv"))):
        frames.append(frame)
except EOFError:
    pass
assert len(frames) == 90, len(frames)
assert all(sum(1 for n in nals(f) if n[0] & 0x1F in (1, 5)) == 1 for f in frames), \
    "each FLV tag must be exactly one picture"
stream = nal + AUD + b"".join(f + AUD for f in frames[:45]) + nal + AUD + \
    b"".join(f + AUD for f in frames[45:])
got = nals(stream)
palettes = [p for p in map(theme_from, got) if p is not None]
video_nals = [n for n in got if theme_from(n) is None and n[0] & 0x1F != 9]

assert len(palettes) == 2, palettes
assert palettes[0].startswith("accent=#3CBF5C\ncolor1=#010101\n"), palettes[0]
assert "rgba" not in palettes[0] and len(palettes[0]) + 16 > 255
assert b"\x00\x00" not in nal[4:], "payload would need emulation prevention"
# x264 repeats SPS/PPS in-band before each IDR; flv_frames re-inserts them.
assert video_nals == nals(video), "conversion changed the video"
print(f"ok: {len(video_nals)} video NALs unchanged, palette of "
      f"{palettes[0].count(chr(10))} colours delivered twice")

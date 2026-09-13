"""Port of H264Stream.drainNals()/onNal() line-for-line, run against a real
capture, to check the access-unit splitter before committing to Java I can't
compile here. Feeds bytes in awkward chunk sizes so buffer-boundary bugs surface.
"""
import sys

buf = bytearray()
au_nals = []
au_has_vcl = False
sps = pps = None
access_units = []
configured_at = None


def find_start_code(frm):
    for i in range(max(frm, 0), len(buf) - 2):
        if buf[i] == 0 and buf[i + 1] == 0 and buf[i + 2] == 1:
            return i
    return -1


def on_nal(nal):
    global au_has_vcl, sps, pps, configured_at
    t = nal[0] & 0x1F
    is_vcl = t in (1, 5)
    starts_new_picture = is_vcl and len(nal) > 1 and (nal[1] & 0x80) != 0
    if starts_new_picture and au_has_vcl:
        flush_au()
    if t == 7: sps = nal
    if t == 8: pps = nal
    au_nals.append(nal)
    au_has_vcl = au_has_vcl or is_vcl
    if configured_at is None and sps is not None and pps is not None:
        configured_at = len(access_units)


def flush_au():
    global au_has_vcl
    if configured_at is None or not au_nals:
        au_nals.clear(); au_has_vcl = False; return
    types = [n[0] & 0x1F for n in au_nals]
    size = sum(4 + len(n) for n in au_nals)
    access_units.append((types, size, 5 in types))
    au_nals.clear()
    au_has_vcl = False


def drain_nals():
    global buf
    first = find_start_code(0)
    if first < 0:
        return
    cursor = first
    while True:
        payload_start = cursor + 3
        nxt = find_start_code(payload_start)
        if nxt < 0:
            break
        payload_end = nxt
        while payload_end > payload_start and buf[payload_end - 1] == 0:
            payload_end -= 1
        if payload_end > payload_start:
            on_nal(bytes(buf[payload_start:payload_end]))
        cursor = nxt
    del buf[:cursor]


data = open(sys.argv[1], "rb").read()
# Deliberately ugly chunk sizes: real socket reads never align to NAL boundaries.
CHUNKS = [1, 3, 7, 4096, 65536, 13, 1500, 999]
i = ci = 0
while i < len(data):
    n = CHUNKS[ci % len(CHUNKS)]; ci += 1
    buf += data[i:i + n]
    i += n
    drain_nals()

names = {1: "P", 5: "IDR", 6: "SEI", 7: "SPS", 8: "PPS", 9: "AUD"}
print(f"input            {len(data)} bytes")
print(f"access units     {len(access_units)}")
print(f"keyframes        {sum(1 for _, _, k in access_units if k)}")
print(f"bytes in AUs     {sum(s for _, s, _ in access_units)}")
print(f"leftover buffer  {len(buf)} bytes (tail awaiting next read)")
print("\nfirst 5 access units:")
for types, size, key in access_units[:5]:
    print(f"  [{' '.join(names.get(t, str(t)) for t in types):<12}] {size:7d} B  {'KEY' if key else ''}")

bad = [t for types, _, _ in access_units for t in types if t not in names]
vcl_counts = [sum(1 for t in types if t in (1, 5)) for types, _, _ in access_units]
print(f"\nunknown NAL types: {set(bad) or 'none'}")
print(f"AUs with != 1 VCL NAL: {sum(1 for c in vcl_counts if c != 1)} (should be 0 for non-sliced encoding)")

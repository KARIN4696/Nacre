#!/usr/bin/env python3
"""
Mozc → Nacre v3: NO compression. Use Mozc's full 2670 POS IDs and connection matrix.

Outputs:
- mozc_dict.bin (gzip TSV): reading\tsurface\tleft_id\tright_id\tcost
- connection.bin (binary): 2-byte signed short[num_ids][num_ids]

The connection matrix is stored as a flat binary file:
  - First 4 bytes: uint32 little-endian = num_ids
  - Then num_ids * num_ids * 2 bytes of int16 little-endian costs
  Total: 4 + 2670*2670*2 ≈ 14.3MB
"""

import sys
import os
import gzip
import struct
import urllib.request

MOZC_BASE = "https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss"
DICT_FILES = [f"dictionary{i:02d}.txt" for i in range(10)] + ["suffix.txt"]
CONN_URL = f"{MOZC_BASE}/connection_single_column.txt"


def download(url, desc=""):
    print(f"  Downloading {desc or url}...", file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "Nacre/1.0"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read().decode("utf-8")


def main():
    output_dir = sys.argv[1] if len(sys.argv) > 1 else "ime-core/src/main/assets/dict"
    os.makedirs(output_dir, exist_ok=True)

    print("=== Mozc → Nacre v3 (Full POS IDs, no compression) ===", file=sys.stderr)

    # 1. Download and write binary connection matrix
    print("\n[1/2] Connection matrix...", file=sys.stderr)
    conn_text = download(CONN_URL, "connection_single_column.txt")
    lines = conn_text.strip().split("\n")
    num_ids = int(lines[0].strip())
    print(f"  Matrix size: {num_ids}x{num_ids}", file=sys.stderr)

    costs = []
    for line in lines[1:]:
        line = line.strip()
        if line:
            costs.append(int(line))

    expected = num_ids * num_ids
    print(f"  Costs read: {len(costs)} (expected {expected})", file=sys.stderr)

    # Write binary: 4-byte header + int16 array
    conn_path = os.path.join(output_dir, "connection.bin")
    with open(conn_path, "wb") as f:
        f.write(struct.pack("<I", num_ids))
        for i in range(expected):
            val = costs[i] if i < len(costs) else 10000
            # Clamp to int16 range
            val = max(-32768, min(32767, val))
            f.write(struct.pack("<h", val))

    conn_size = os.path.getsize(conn_path)
    print(f"  Written: {conn_path} ({conn_size/1024/1024:.1f}MB)", file=sys.stderr)

    # 2. Download dictionary files (keep original left_id/right_id)
    print("\n[2/2] Dictionary files...", file=sys.stderr)
    all_entries = []
    for fname in DICT_FILES:
        url = f"{MOZC_BASE}/{fname}"
        text = download(url, fname)
        if not text:
            continue
        count = 0
        for line in text.strip().split("\n"):
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 5:
                continue
            reading = parts[0]
            left_id = int(parts[1])
            right_id = int(parts[2])
            cost = int(parts[3])
            surface = parts[4]
            if reading and surface:
                all_entries.append((reading, surface, left_id, right_id, cost))
                count += 1
        print(f"    {fname}: {count}", file=sys.stderr)

    # Deduplicate
    seen = {}
    for r, s, lid, rid, c in all_entries:
        key = (r, s)
        if key not in seen or c < seen[key][2]:
            seen[key] = (lid, rid, c)

    sorted_entries = sorted(
        ((r, s, lid, rid, c) for (r, s), (lid, rid, c) in seen.items()),
        key=lambda x: (x[0], x[4])
    )

    print(f"\n  Total entries: {len(sorted_entries)}", file=sys.stderr)

    # Write gzipped dict
    dict_path = os.path.join(output_dir, "mozc_dict.bin")
    import io
    buf = io.BytesIO()
    for r, s, lid, rid, c in sorted_entries:
        buf.write(f"{r}\t{s}\t{lid}\t{rid}\t{c}\n".encode("utf-8"))

    raw_size = buf.tell()
    buf.seek(0)
    with gzip.open(dict_path, "wb", compresslevel=9) as f:
        f.write(buf.read())

    gz_size = os.path.getsize(dict_path)
    print(f"  Dict: {raw_size/1024/1024:.1f}MB raw → {gz_size/1024/1024:.1f}MB gzip", file=sys.stderr)

    print(f"\n=== Done ===", file=sys.stderr)
    print(f"  {len(sorted_entries)} entries, {num_ids}x{num_ids} connection matrix", file=sys.stderr)


if __name__ == "__main__":
    main()

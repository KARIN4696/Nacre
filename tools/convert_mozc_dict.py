#!/usr/bin/env python3
"""
Convert Mozc OSS dictionary to Nacre IME format.

Downloads Mozc dictionary files from GitHub and converts them to:
1. mozc_dict.tsv — main dictionary (reading\tsurface\tleft_group\tright_group\tcost)
2. connection_group.tsv — 14x14 connection cost matrix

Mozc POS IDs (0-2669) are mapped to 14 Nacre POS groups.
Mozc connection matrix (2652x2652) is collapsed to 14x14 by averaging.

Usage: python3 convert_mozc_dict.py [--output-dir ime-core/src/main/assets/dict]
"""

import sys
import os
import gzip
import urllib.request
import re
from collections import defaultdict

MOZC_BASE = "https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss"
DICT_FILES = [f"dictionary{i:02d}.txt" for i in range(10)] + ["suffix.txt"]
ID_DEF_URL = f"{MOZC_BASE}/id.def"
CONN_URL = f"{MOZC_BASE}/connection_single_column.txt"

# Nacre POS groups (14 groups, 0-13)
# 0=BOS/EOS, 1=名詞, 2=動詞, 3=形容詞, 4=助動詞, 5=助詞,
# 6=副詞, 7=連体詞, 8=接続詞, 9=感動詞, 10=接頭詞, 11=記号, 12=フィラー, 13=その他
POS_GROUP_MAP = {
    "BOS/EOS": 0,
    "名詞": 1,
    "動詞": 2,
    "形容詞": 3,
    "助動詞": 4,
    "助詞": 5,
    "副詞": 6,
    "連体詞": 7,
    "接続詞": 8,
    "感動詞": 9,
    "接頭詞": 10,
    "接頭辞": 10,
    "記号": 11,
    "フィラー": 12,
    "その他": 13,
    "接尾辞": 1,  # Suffix → noun group
    "接尾": 1,
    "形状詞": 3,  # Na-adjective → adjective group
    "補助記号": 11,
    "空白": 11,
    "代名詞": 1,  # Pronoun → noun
}

NUM_GROUPS = 14


def download(url, desc=""):
    """Download a URL and return content as string."""
    print(f"  Downloading {desc or url}...", file=sys.stderr)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Nacre-Dict-Builder/1.0"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.read().decode("utf-8")
    except Exception as e:
        print(f"  ERROR downloading {url}: {e}", file=sys.stderr)
        return None


def parse_id_def(text):
    """Parse Mozc id.def to build POS ID → Nacre group mapping."""
    id_to_group = {}
    for line in text.strip().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(" ", 1)
        if len(parts) < 2:
            continue
        pos_id = int(parts[0])
        pos_str = parts[1]

        # Extract first POS category (e.g., "名詞,固有名詞,..." → "名詞")
        first_pos = pos_str.split(",")[0]
        group = POS_GROUP_MAP.get(first_pos, 13)  # Default to その他
        id_to_group[pos_id] = group

    # BOS/EOS is typically ID 0
    id_to_group[0] = 0
    print(f"  Parsed {len(id_to_group)} POS ID mappings", file=sys.stderr)
    return id_to_group


def parse_connection_matrix(text, id_to_group):
    """Parse Mozc connection_single_column.txt and collapse to 14x14."""
    lines = text.strip().split("\n")
    num_ids = int(lines[0].strip())
    print(f"  Connection matrix: {num_ids}x{num_ids}", file=sys.stderr)

    # Read flat costs
    costs = []
    for line in lines[1:]:
        line = line.strip()
        if line:
            costs.append(int(line))

    expected = num_ids * num_ids
    if len(costs) < expected:
        print(f"  WARNING: expected {expected} costs, got {len(costs)}", file=sys.stderr)

    # Collapse to 14x14 by averaging costs per group pair
    group_sums = [[0] * NUM_GROUPS for _ in range(NUM_GROUPS)]
    group_counts = [[0] * NUM_GROUPS for _ in range(NUM_GROUPS)]

    for right_id in range(min(num_ids, len(id_to_group))):
        for left_id in range(min(num_ids, len(id_to_group))):
            idx = right_id * num_ids + left_id
            if idx >= len(costs):
                continue
            rg = id_to_group.get(right_id, 13)
            lg = id_to_group.get(left_id, 13)
            group_sums[rg][lg] += costs[idx]
            group_counts[rg][lg] += 1

    # Average
    matrix = [[0] * NUM_GROUPS for _ in range(NUM_GROUPS)]
    for r in range(NUM_GROUPS):
        for l in range(NUM_GROUPS):
            if group_counts[r][l] > 0:
                matrix[r][l] = group_sums[r][l] // group_counts[r][l]
            else:
                matrix[r][l] = 10000  # Default high cost

    return matrix


def is_hiragana(s):
    """Check if string is all hiragana."""
    return all("\u3040" <= c <= "\u309F" or c == "ー" for c in s)


def parse_dict_file(text, id_to_group):
    """Parse a Mozc dictionary file. Returns list of (reading, surface, left_group, right_group, cost)."""
    entries = []
    for line in text.strip().split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 5:
            continue

        reading = parts[0]      # Hiragana reading
        left_id = int(parts[1])
        right_id = int(parts[2])
        cost = int(parts[3])
        surface = parts[4]

        # Skip invalid readings
        if not reading or not surface:
            continue

        # Map POS IDs to groups
        left_group = id_to_group.get(left_id, 13)
        right_group = id_to_group.get(right_id, 13)

        entries.append((reading, surface, left_group, right_group, cost))

    return entries


def main():
    output_dir = sys.argv[1] if len(sys.argv) > 1 else "ime-core/src/main/assets/dict"
    os.makedirs(output_dir, exist_ok=True)

    print("=== Mozc → Nacre Dictionary Converter ===", file=sys.stderr)

    # Step 1: Download and parse id.def
    print("\n[1/3] Downloading POS definitions...", file=sys.stderr)
    id_def_text = download(ID_DEF_URL, "id.def")
    if not id_def_text:
        print("FATAL: Cannot download id.def", file=sys.stderr)
        sys.exit(1)
    id_to_group = parse_id_def(id_def_text)

    # Step 2: Download and convert connection matrix
    print("\n[2/3] Downloading connection matrix...", file=sys.stderr)
    conn_text = download(CONN_URL, "connection_single_column.txt")
    if not conn_text:
        print("FATAL: Cannot download connection matrix", file=sys.stderr)
        sys.exit(1)
    matrix = parse_connection_matrix(conn_text, id_to_group)

    conn_path = os.path.join(output_dir, "connection_group.tsv")
    with open(conn_path, "w") as f:
        f.write(f"# Mozc connection cost matrix collapsed to {NUM_GROUPS} groups\n")
        f.write(f"# Groups: BOS/EOS(0) 名詞(1) 動詞(2) 形容詞(3) 助動詞(4) 助詞(5)\n")
        f.write(f"# 副詞(6) 連体詞(7) 接続詞(8) 感動詞(9) 接頭詞(10) 記号(11) フィラー(12) その他(13)\n")
        f.write(f"{NUM_GROUPS}\n")
        for row in matrix:
            f.write("\t".join(str(v) for v in row) + "\n")
    print(f"  Written: {conn_path}", file=sys.stderr)

    # Step 3: Download and convert dictionary files
    print("\n[3/3] Downloading dictionary files...", file=sys.stderr)
    all_entries = []
    for fname in DICT_FILES:
        url = f"{MOZC_BASE}/{fname}"
        text = download(url, fname)
        if text:
            entries = parse_dict_file(text, id_to_group)
            all_entries.extend(entries)
            print(f"    {fname}: {len(entries)} entries", file=sys.stderr)

    # Deduplicate: keep lowest cost per (reading, surface) pair
    seen = {}
    for reading, surface, lg, rg, cost in all_entries:
        key = (reading, surface)
        if key not in seen or cost < seen[key][2]:
            seen[key] = (lg, rg, cost)

    # Sort by reading for efficient loading
    sorted_entries = sorted(
        ((r, s, lg, rg, c) for (r, s), (lg, rg, c) in seen.items()),
        key=lambda x: (x[0], x[4])
    )

    # Write dictionary
    dict_path = os.path.join(output_dir, "mozc_dict.tsv")
    with open(dict_path, "w") as f:
        for reading, surface, lg, rg, cost in sorted_entries:
            f.write(f"{reading}\t{surface}\t{lg}\t{rg}\t{cost}\n")

    print(f"\n  Total entries: {len(sorted_entries)}", file=sys.stderr)
    print(f"  Written: {dict_path}", file=sys.stderr)

    # Also create gzipped version
    gz_path = dict_path + ".gz"
    with open(dict_path, "rb") as f_in:
        with gzip.open(gz_path, "wb", compresslevel=9) as f_out:
            f_out.write(f_in.read())
    raw_size = os.path.getsize(dict_path)
    gz_size = os.path.getsize(gz_path)
    print(f"  Raw: {raw_size/1024/1024:.1f}MB → Gzip: {gz_size/1024/1024:.1f}MB", file=sys.stderr)

    # Stats
    print(f"\n=== Done ===", file=sys.stderr)
    print(f"  Dictionary: {len(sorted_entries)} entries", file=sys.stderr)
    print(f"  Connection: {NUM_GROUPS}x{NUM_GROUPS} matrix", file=sys.stderr)
    print(f"  Output: {output_dir}/", file=sys.stderr)


if __name__ == "__main__":
    main()

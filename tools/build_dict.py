#!/usr/bin/env python3
"""
Build compact Nacre dictionary from Mozc OSS data.

Outputs:
  - nacre_dict.bin: Binary dictionary (reading→surface with POS group + cost)
  - connection.bin: Grouped connection cost matrix (16×16 groups)

POS Group mapping (15 groups + BOS/EOS):
  0: BOS/EOS
  1: 名詞 (noun)
  2: 動詞 (verb)
  3: 形容詞 (adjective)
  4: 助動詞 (auxiliary verb)
  5: 助詞 (particle)
  6: 副詞 (adverb)
  7: 連体詞 (prenominal)
  8: 接続詞 (conjunction)
  9: 感動詞 (interjection)
  10: 接頭詞 (prefix)
  11: 記号 (symbol)
  12: フィラー (filler)
  13: その他 (other)
"""

import sys
import os
import struct
from collections import defaultdict

MOZC_DIR = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'dict', 'mozc')
OUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'dict')

# POS category → group ID
POS_GROUP_MAP = {
    'BOS/EOS': 0,
    '名詞': 1,
    '動詞': 2,
    '形容詞': 3,
    '助動詞': 4,
    '助詞': 5,
    '副詞': 6,
    '連体詞': 7,
    '接続詞': 8,
    '感動詞': 9,
    '接頭詞': 10,
    '記号': 11,
    'フィラー': 12,
    'その他': 13,
}
NUM_GROUPS = 14

def load_id_def():
    """Load id.def → map POS ID to group ID."""
    id_to_group = {}
    with open(os.path.join(MOZC_DIR, 'id.def'), 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split(' ', 1)
            pos_id = int(parts[0])
            pos_str = parts[1]  # e.g. "名詞,一般,*,*,*,*,*"
            category = pos_str.split(',')[0]
            group = POS_GROUP_MAP.get(category, 13)  # default to その他
            id_to_group[pos_id] = group
    return id_to_group

def build_grouped_connection(id_to_group, num_pos):
    """Read connection_single_column.txt and compute averaged group connection costs."""
    print("Loading connection matrix...")

    # Accumulators for averaging
    group_sum = [[0.0] * NUM_GROUPS for _ in range(NUM_GROUPS)]
    group_count = [[0] * NUM_GROUPS for _ in range(NUM_GROUPS)]

    with open(os.path.join(MOZC_DIR, 'connection_single_column.txt'), 'r') as f:
        header = int(f.readline().strip())
        assert header == num_pos, f"Expected {num_pos} POS IDs, got {header}"

        idx = 0
        total = num_pos * num_pos
        for line in f:
            cost = int(line.strip())
            left = idx // num_pos
            right = idx % num_pos

            lg = id_to_group.get(left, 13)
            rg = id_to_group.get(right, 13)

            group_sum[lg][rg] += cost
            group_count[lg][rg] += 1

            idx += 1
            if idx % 1000000 == 0:
                print(f"  {idx}/{total} ({100*idx//total}%)")

    # Compute averages
    connection = [[0] * NUM_GROUPS for _ in range(NUM_GROUPS)]
    for i in range(NUM_GROUPS):
        for j in range(NUM_GROUPS):
            if group_count[i][j] > 0:
                connection[i][j] = int(group_sum[i][j] / group_count[i][j])
            else:
                connection[i][j] = 5000  # default high cost

    return connection

def build_dictionary(id_to_group):
    """Read all Mozc dictionary files and output compact TSV."""
    print("Building dictionary...")
    entries = []
    seen = set()

    for i in range(10):
        fname = f'dictionary{i:02d}.txt'
        fpath = os.path.join(MOZC_DIR, fname)
        if not os.path.exists(fpath):
            continue
        print(f"  Loading {fname}...")
        with open(fpath, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                parts = line.split('\t')
                if len(parts) < 5:
                    continue
                reading = parts[0]
                left_id = int(parts[1])
                right_id = int(parts[2])
                cost = int(parts[3])
                surface = parts[4]

                # Map to group IDs
                left_group = id_to_group.get(left_id, 13)
                right_group = id_to_group.get(right_id, 13)

                # Deduplicate (keep lowest cost)
                key = (reading, surface)
                if key in seen:
                    continue
                seen.add(key)

                entries.append((reading, surface, left_group, right_group, cost))

    # Sort by reading for binary search
    entries.sort(key=lambda e: e[0])
    print(f"  Total unique entries: {len(entries)}")
    return entries

def write_tsv_dict(entries, connection):
    """Write compact TSV dictionary and connection costs."""
    # Dictionary: reading\tsurface\tleft_group\tright_group\tcost
    dict_path = os.path.join(OUT_DIR, 'mozc_dict.tsv')
    print(f"Writing {dict_path}...")
    with open(dict_path, 'w', encoding='utf-8') as f:
        f.write(f"# Nacre dictionary (from Mozc OSS)\n")
        f.write(f"# Format: reading\\tsurface\\tleft_group\\tright_group\\tcost\n")
        for reading, surface, lg, rg, cost in entries:
            f.write(f"{reading}\t{surface}\t{lg}\t{rg}\t{cost}\n")

    # Connection costs: 14×14 matrix
    conn_path = os.path.join(OUT_DIR, 'connection_group.tsv')
    print(f"Writing {conn_path}...")
    with open(conn_path, 'w', encoding='utf-8') as f:
        f.write(f"# Connection costs ({NUM_GROUPS}x{NUM_GROUPS} POS group matrix)\n")
        f.write(f"# Groups: 0=BOS/EOS 1=名詞 2=動詞 3=形容詞 4=助動詞 5=助詞 6=副詞 7=連体詞 8=接続詞 9=感動詞 10=接頭詞 11=記号 12=フィラー 13=その他\n")
        f.write(f"{NUM_GROUPS}\n")
        for i in range(NUM_GROUPS):
            f.write('\t'.join(str(connection[i][j]) for j in range(NUM_GROUPS)) + '\n')

    print("Done!")
    print(f"  Dictionary: {os.path.getsize(dict_path) / 1024 / 1024:.1f} MB")
    print(f"  Connection: {os.path.getsize(conn_path)} bytes")

def main():
    id_to_group = load_id_def()
    num_pos = max(id_to_group.keys()) + 1
    print(f"Loaded {len(id_to_group)} POS IDs → {NUM_GROUPS} groups")

    connection = build_grouped_connection(id_to_group, num_pos)

    # Print connection matrix for debugging
    labels = ['BOS', '名詞', '動詞', '形容', '助動', '助詞', '副詞', '連体', '接続', '感動', '接頭', '記号', 'フィラ', 'その他']
    print("\nGrouped connection costs:")
    print(f"{'':>6}", end='')
    for l in labels:
        print(f"{l:>6}", end='')
    print()
    for i in range(NUM_GROUPS):
        print(f"{labels[i]:>6}", end='')
        for j in range(NUM_GROUPS):
            print(f"{connection[i][j]:>6}", end='')
        print()
    print()

    entries = build_dictionary(id_to_group)
    write_tsv_dict(entries, connection)

if __name__ == '__main__':
    main()

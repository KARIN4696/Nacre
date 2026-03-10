#!/usr/bin/env python3
"""
Build Nacre dictionary v4 from Mozc OSS data.
- cost < 12000 entries (maximum coverage, near-full Mozc)
- Max 20 entries per reading (keep lowest cost)
- Auto-add hiragana-as-is candidates for readings >= 2 chars
- Auto-add katakana candidates for readings >= 2 chars
- Output: gzip-compressed TSV as .bin (avoids aapt2 re-compression)
"""

import gzip
import os
from collections import defaultdict

MOZC_DIR = os.path.join(os.path.dirname(__file__), 'mozc_raw')
OUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'dict')

# POS category → group ID
POS_GROUP_MAP = {
    'BOS/EOS': 0, '名詞': 1, '動詞': 2, '形容詞': 3, '助動詞': 4,
    '助詞': 5, '副詞': 6, '連体詞': 7, '接続詞': 8, '感動詞': 9,
    '接頭詞': 10, '記号': 11, 'フィラー': 12, 'その他': 13,
}

HIRAGANA_TO_KATAKANA = str.maketrans(
    'ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔ',
    'ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴ'
)

def is_hiragana(s):
    return all('\u3040' <= c <= '\u309f' or c == 'ー' for c in s)

def to_katakana(s):
    return s.translate(HIRAGANA_TO_KATAKANA)

def load_id_def():
    id_to_group = {}
    with open(os.path.join(MOZC_DIR, 'id.def'), 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split(' ', 1)
            pos_id = int(parts[0])
            category = parts[1].split(',')[0]
            id_to_group[pos_id] = POS_GROUP_MAP.get(category, 13)
    return id_to_group

def main():
    id_to_group = load_id_def()
    print(f"Loaded {len(id_to_group)} POS IDs")

    # Collect all entries grouped by reading
    readings = defaultdict(list)

    for i in range(10):
        fname = f'dictionary{i:02d}.txt'
        fpath = os.path.join(MOZC_DIR, fname)
        if not os.path.exists(fpath):
            continue
        print(f"Loading {fname}...")
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

                if cost >= 12000:
                    continue

                lg = id_to_group.get(left_id, 13)
                rg = id_to_group.get(right_id, 13)

                readings[reading].append((surface, lg, rg, cost))

    print(f"Raw readings: {len(readings)}")

    # For each reading: sort by cost, deduplicate surfaces, keep top 10
    output = []

    for reading, entries in readings.items():
        entries.sort(key=lambda e: e[3])

        seen_surfaces = set()
        filtered = []
        for surface, lg, rg, cost in entries:
            if surface not in seen_surfaces:
                seen_surfaces.add(surface)
                filtered.append((surface, lg, rg, cost))
                if len(filtered) >= 20:
                    break

        for surface, lg, rg, cost in filtered:
            output.append((reading, surface, lg, rg, cost))

        # Auto-add hiragana/katakana for readings >= 2 chars
        if is_hiragana(reading) and len(reading) >= 2:
            if reading not in seen_surfaces:
                output.append((reading, reading, 1, 1, 5500))

            katakana = to_katakana(reading)
            if katakana not in seen_surfaces and katakana != reading:
                output.append((reading, katakana, 1, 1, 5800))

    # Sort by reading for binary search
    output.sort(key=lambda e: e[0])

    print(f"Total entries (with auto-add): {len(output)}")

    # Write gzip-compressed as .bin (avoids aapt2 re-compression issue)
    dict_path = os.path.join(OUT_DIR, 'mozc_dict.bin')
    print(f"Writing {dict_path} (gzip)...")
    with gzip.open(dict_path, 'wt', encoding='utf-8', compresslevel=9) as f:
        for reading, surface, lg, rg, cost in output:
            f.write(f"{reading}\t{surface}\t{lg}\t{rg}\t{cost}\n")

    raw_size = sum(len(f"{r}\t{s}\t{lg}\t{rg}\t{c}\n".encode('utf-8')) for r, s, lg, rg, c in output)
    compressed_size = os.path.getsize(dict_path)
    print(f"Raw TSV: {raw_size / 1024 / 1024:.1f} MB")
    print(f"Compressed: {compressed_size / 1024 / 1024:.1f} MB")
    print(f"Ratio: {compressed_size / raw_size * 100:.1f}%")
    print(f"Done! {len(output)} entries")

    # Also delete old TSV if it exists
    old_tsv = os.path.join(OUT_DIR, 'mozc_dict.tsv')
    if os.path.exists(old_tsv):
        os.remove(old_tsv)
        print(f"Removed old {old_tsv}")

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Build Nacre binary dictionary from Mozc OSS data.

Binary format:
  Header (16 bytes): magic("NCRD") + version(u32) + reading_count(u32) + entry_count(u32)
  Connection matrix: num_groups(u8) + num_groups*num_groups*u16
  Reading index (12 bytes each, sorted for binary search):
    reading_offset(u32) + reading_length(u16) + entries_offset(u32) + entries_count(u16)
  Entry blocks (10 bytes each):
    surface_offset(u32) + surface_length(u16) + left_group(u8) + right_group(u8) + cost(u16)
  String pool: deduplicated UTF-8 strings
"""

import os
import struct
from collections import defaultdict

MOZC_DIR = os.path.join(os.path.dirname(__file__), 'mozc_raw')
OUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'dict')
CONNECTION_TSV = os.path.join(OUT_DIR, 'connection_group.tsv')

COST_THRESHOLD = 8000
MAX_CANDIDATES = 8
HIRAGANA_AUTO_COST = 5500
KATAKANA_AUTO_COST = 5800
VERSION = 1

# POS category -> group ID (same as build_dict_v2.py)
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


def load_connection_matrix():
    """Load connection_group.tsv and return (num_groups, flat list of u16 costs)."""
    matrix = []
    num_groups = 0
    with open(CONNECTION_TSV, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) == 1:
                num_groups = int(parts[0])
                continue
            # Each row: cost0 cost1 ... costN
            row = [int(x) for x in parts]
            matrix.extend(row)
    assert len(matrix) == num_groups * num_groups, \
        f"Expected {num_groups}x{num_groups}={num_groups*num_groups} entries, got {len(matrix)}"
    return num_groups, matrix


class StringPool:
    """Deduplicated UTF-8 string pool."""

    def __init__(self):
        self._strings = {}  # string -> offset
        self._data = bytearray()

    def add(self, s):
        if s in self._strings:
            return self._strings[s]
        offset = len(self._data)
        encoded = s.encode('utf-8')
        self._data.extend(encoded)
        self._strings[s] = offset
        return offset

    def get_offset(self, s):
        return self._strings[s]

    def get_data(self):
        return bytes(self._data)


def main():
    id_to_group = load_id_def()
    print(f"Loaded {len(id_to_group)} POS IDs")

    # Load connection matrix
    num_groups, conn_matrix = load_connection_matrix()
    print(f"Connection matrix: {num_groups}x{num_groups}")

    # Collect all entries grouped by reading
    readings = defaultdict(list)

    total_raw = 0
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

                total_raw += 1
                if cost >= COST_THRESHOLD:
                    continue

                lg = id_to_group.get(left_id, 13)
                rg = id_to_group.get(right_id, 13)
                readings[reading].append((surface, lg, rg, cost))

    print(f"Raw readings: {len(readings)} (from {total_raw} total raw entries)")

    # For each reading: sort by cost, deduplicate surfaces, keep top MAX_CANDIDATES
    # Group entries by reading for the binary format
    reading_entries = {}  # reading -> [(surface, lg, rg, cost)]

    for reading, entries in readings.items():
        entries.sort(key=lambda e: e[3])

        seen_surfaces = set()
        filtered = []
        for surface, lg, rg, cost in entries:
            if surface not in seen_surfaces:
                seen_surfaces.add(surface)
                filtered.append((surface, lg, rg, cost))
                if len(filtered) >= MAX_CANDIDATES - 2:  # Reserve 2 slots for auto-add
                    break

        # Auto-add hiragana/katakana
        if is_hiragana(reading) and len(reading) >= 2:
            if reading not in seen_surfaces:
                filtered.append((reading, 1, 1, HIRAGANA_AUTO_COST))
                seen_surfaces.add(reading)

            katakana = to_katakana(reading)
            if katakana not in seen_surfaces and katakana != reading:
                filtered.append((katakana, 1, 1, KATAKANA_AUTO_COST))
                seen_surfaces.add(katakana)

        # Re-sort by cost and limit to MAX_CANDIDATES
        filtered.sort(key=lambda e: e[3])
        filtered = filtered[:MAX_CANDIDATES]

        reading_entries[reading] = filtered

    # Sort readings lexicographically
    sorted_readings = sorted(reading_entries.keys())

    total_entries = sum(len(reading_entries[r]) for r in sorted_readings)
    print(f"Readings: {len(sorted_readings)}, Total entries: {total_entries}")

    # Build string pool (add all strings first)
    pool = StringPool()
    for reading in sorted_readings:
        pool.add(reading)
        for surface, lg, rg, cost in reading_entries[reading]:
            pool.add(surface)

    # Build binary
    reading_count = len(sorted_readings)

    # Calculate offsets
    header_size = 16
    conn_size = 1 + num_groups * num_groups * 2  # u8 + matrix
    reading_index_size = reading_count * 12
    entry_block_size = total_entries * 10
    string_pool_data = pool.get_data()

    print(f"Header: {header_size} bytes")
    print(f"Connection matrix: {conn_size} bytes")
    print(f"Reading index: {reading_index_size} bytes")
    print(f"Entry blocks: {entry_block_size} bytes")
    print(f"String pool: {len(string_pool_data)} bytes")

    # Write binary file
    os.makedirs(OUT_DIR, exist_ok=True)
    out_path = os.path.join(OUT_DIR, 'nacre_dict.bin')

    with open(out_path, 'wb') as f:
        # Header
        f.write(b'NCRD')
        f.write(struct.pack('<I', VERSION))
        f.write(struct.pack('<I', reading_count))
        f.write(struct.pack('<I', total_entries))

        # Connection matrix
        f.write(struct.pack('<B', num_groups))
        for cost in conn_matrix:
            f.write(struct.pack('<H', cost))

        # Reading index + Entry blocks
        # First pass: compute entry offsets
        entry_offset = 0
        reading_index_data = bytearray()
        entry_block_data = bytearray()

        for reading in sorted_readings:
            entries = reading_entries[reading]
            r_offset = pool.get_offset(reading)
            r_len = len(reading.encode('utf-8'))

            # Reading index entry
            reading_index_data.extend(struct.pack('<I', r_offset))
            reading_index_data.extend(struct.pack('<H', r_len))
            reading_index_data.extend(struct.pack('<I', entry_offset))
            reading_index_data.extend(struct.pack('<H', len(entries)))

            # Entry block entries
            for surface, lg, rg, cost in entries:
                s_offset = pool.get_offset(surface)
                s_len = len(surface.encode('utf-8'))
                entry_block_data.extend(struct.pack('<I', s_offset))
                entry_block_data.extend(struct.pack('<H', s_len))
                entry_block_data.extend(struct.pack('<B', lg))
                entry_block_data.extend(struct.pack('<B', rg))
                entry_block_data.extend(struct.pack('<H', cost))
                entry_offset += 10

        f.write(reading_index_data)
        f.write(entry_block_data)
        f.write(string_pool_data)

    file_size = os.path.getsize(out_path)
    print(f"\nOutput: {out_path}")
    print(f"File size: {file_size:,} bytes ({file_size / 1024 / 1024:.2f} MB)")
    print(f"  Header:           {header_size:>10,} bytes")
    print(f"  Connection:       {conn_size:>10,} bytes")
    print(f"  Reading index:    {reading_index_size:>10,} bytes ({reading_count:,} readings)")
    print(f"  Entry blocks:     {entry_block_size:>10,} bytes ({total_entries:,} entries)")
    print(f"  String pool:      {len(string_pool_data):>10,} bytes")
    print(f"  Total:            {header_size + conn_size + reading_index_size + entry_block_size + len(string_pool_data):>10,} bytes")


if __name__ == '__main__':
    main()

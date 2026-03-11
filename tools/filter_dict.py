#!/usr/bin/env python3
"""Filter mozc_dict.bin to reduce APK size.

Target: ~2M entries, ~25MB gzip.

Strategy:
- cost <= 4000: keep all (~1.06M core Mozc words)
- cost 4001-5500: keep all (~530K, useful vocabulary)
- cost 5501-6500: keep only if surface has kanji (~600K -> ~500K)
- cost 6501-7500: keep only if surface has kanji AND reading <= 6 chars (~800K -> ~200K)
- cost > 7500: drop all (~1.3M low-value entries)
- reading length > 15: drop (very long compounds add little value)
"""

import gzip
import os
import re
import time

DICT_PATH = os.path.expanduser(
    "~/Nacre/ime-core/src/main/assets/dict/mozc_dict.bin"
)

HAS_KANJI_RE = re.compile(r'[\u4E00-\u9FFF\u3400-\u4DBF]')
HIRAGANA_RE = re.compile(r'^[\u3040-\u309F]+$')


def has_kanji(s: str) -> bool:
    return bool(HAS_KANJI_RE.search(s))


def is_all_hiragana(s: str) -> bool:
    return bool(HIRAGANA_RE.match(s))


def main():
    orig_size = os.path.getsize(DICT_PATH)
    print(f"Original file size: {orig_size / 1024 / 1024:.1f} MB")

    t0 = time.time()
    kept = []
    total = 0
    stats = {
        "drop_long_reading": 0,
        "drop_gt7500": 0,
        "drop_6501_7500": 0,
        "drop_5501_6500": 0,
        "drop_4001_5500": 0,
    }

    with gzip.open(DICT_PATH, "rt", encoding="utf-8") as f:
        for line in f:
            total += 1
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                kept.append(line)
                continue

            reading = parts[0]
            surface = parts[1]
            cost = int(parts[4])

            # Filter: reading too long
            if len(reading) > 15:
                stats["drop_long_reading"] += 1
                continue

            # Tier 1: core vocabulary - keep all
            if cost <= 4000:
                kept.append(line)
                continue

            # Tier 2: cost 4001-5500 - keep unless hiragana identity
            if cost <= 5500:
                if surface == reading and is_all_hiragana(reading):
                    stats["drop_4001_5500"] += 1
                    continue
                kept.append(line)
                continue

            # Tier 3: cost 5501-6500 - keep only kanji conversions
            if cost <= 6500:
                if has_kanji(surface):
                    kept.append(line)
                else:
                    stats["drop_5501_6500"] += 1
                continue

            # Tier 4: cost 6501-7500 - keep only short kanji conversions
            if cost <= 7500:
                if has_kanji(surface) and len(reading) <= 6:
                    kept.append(line)
                else:
                    stats["drop_6501_7500"] += 1
                continue

            # Tier 5: cost > 7500 - drop
            stats["drop_gt7500"] += 1

    t1 = time.time()
    print(f"Read {total:,} entries in {t1 - t0:.1f}s")
    for k, v in stats.items():
        print(f"  {k}: {v:,}")
    print(f"  Kept: {len(kept):,} ({len(kept) * 100 / total:.1f}%)")

    # Write back
    print("Writing filtered dictionary...")
    t2 = time.time()
    with gzip.open(DICT_PATH, "wt", encoding="utf-8", compresslevel=9) as f:
        for line in kept:
            f.write(line)
    t3 = time.time()

    new_size = os.path.getsize(DICT_PATH)
    print(f"Write completed in {t3 - t2:.1f}s")
    print()
    print("=== Summary ===")
    print(f"  Original entries: {total:,}")
    print(f"  Filtered entries: {len(kept):,}")
    print(f"  Removed:          {total - len(kept):,}")
    print(f"  Original size:    {orig_size / 1024 / 1024:.1f} MB")
    print(f"  New size:         {new_size / 1024 / 1024:.1f} MB")
    print(f"  Reduction:        {(1 - new_size / orig_size) * 100:.1f}%")


if __name__ == "__main__":
    main()

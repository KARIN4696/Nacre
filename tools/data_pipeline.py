#!/usr/bin/env python3
"""
Comprehensive data pipeline for Nacre IME dictionary.

Steps:
1. Backup current mozc_dict.bin
2. Load base Mozc dictionary (the original, before NEologd merge)
3. Re-merge NEologd with fixed verb POS mapping
4. Download & merge Wikipedia Japanese article titles
5. Download & merge Mozc emoji data
6. Try to download station names (ekidata.jp)
7. Deduplicate (existing Mozc entries take priority)
8. Apply filter_dict.py filtering
9. Write final mozc_dict.bin

Usage: python3 tools/data_pipeline.py
"""

import sys
import os
import gzip
import io
import lzma
import csv
import urllib.request
import re
import time
import shutil

# Paths
BASE_DIR = os.path.expanduser("~/Nacre")
DICT_DIR = os.path.join(BASE_DIR, "ime-core/src/main/assets/dict")
DICT_PATH = os.path.join(DICT_DIR, "mozc_dict.bin")
BACKUP_PATH = DICT_PATH + ".bak"
TMP_DIR = os.environ.get("TMPDIR", "/data/data/com.termux/files/usr/tmp")

# --- Shared utilities ---

HAS_KANJI_RE = re.compile(r'[\u4E00-\u9FFF\u3400-\u4DBF]')
HIRAGANA_RE = re.compile(r'^[\u3040-\u309F]+$')


def has_kanji(s):
    return bool(HAS_KANJI_RE.search(s))


def is_all_hiragana(s):
    return bool(HIRAGANA_RE.match(s))


def katakana_to_hiragana(text):
    result = []
    for ch in text:
        cp = ord(ch)
        if 0x30A1 <= cp <= 0x30F6:
            result.append(chr(cp - 0x60))
        else:
            result.append(ch)
    return "".join(result)


def load_mozc_dict(path):
    """Load gzipped mozc_dict.bin -> dict {(reading,surface): (lid,rid,cost)}."""
    entries = {}
    if not os.path.exists(path):
        print(f"  Warning: {path} not found", file=sys.stderr)
        return entries
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) < 5:
                continue
            reading, surface = parts[0], parts[1]
            lid, rid, cost = int(parts[2]), int(parts[3]), int(parts[4])
            key = (reading, surface)
            if key not in entries or cost < entries[key][2]:
                entries[key] = (lid, rid, cost)
    return entries


def write_dict(entries, path):
    """Write entries dict to gzipped TSV."""
    sorted_entries = sorted(
        ((r, s, lid, rid, c) for (r, s), (lid, rid, c) in entries.items()),
        key=lambda x: (x[0], x[4])
    )
    total = len(sorted_entries)
    buf = io.BytesIO()
    for r, s, lid, rid, c in sorted_entries:
        buf.write(f"{r}\t{s}\t{lid}\t{rid}\t{c}\n".encode("utf-8"))
    raw_size = buf.tell()
    buf.seek(0)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with gzip.open(path, "wb", compresslevel=9) as f:
        f.write(buf.read())
    gz_size = os.path.getsize(path)
    print(f"  Written: {total:,} entries, raw {raw_size/1024/1024:.1f}MB, gz {gz_size/1024/1024:.1f}MB", file=sys.stderr)
    return total, gz_size


def download_url(url, desc="", timeout=300):
    """Download URL and return bytes."""
    print(f"  Downloading {desc or url}...", file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "Nacre/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


# ============================================================
# STEP 1: NEologd with fixed POS mapping
# ============================================================

NEOLOGD_BASE = "https://raw.githubusercontent.com/neologd/mecab-ipadic-neologd/master/seed"
SEED_FILES = [
    "mecab-user-dict-seed.20200910.csv.xz",
    "neologd-adjective-exp-dict-seed.20151126.csv.xz",
    "neologd-adjective-std-dict-seed.20151126.csv.xz",
    "neologd-adjective-verb-dict-seed.20160324.csv.xz",
    "neologd-adverb-dict-seed.20150623.csv.xz",
    "neologd-common-noun-ortho-variant-dict-seed.20170228.csv.xz",
    "neologd-interjection-dict-seed.20170216.csv.xz",
    "neologd-noun-sahen-conn-ortho-variant-dict-seed.20160323.csv.xz",
    "neologd-proper-noun-ortho-variant-dict-seed.20161110.csv.xz",
]

# Verb conjugation type -> Mozc ID (from mozc_raw/id.def, 動詞,自立,基本形)
VERB_CONJ_MAP = {
    "五段・カ行イ音便":     (723, 723),
    "五段・カ行促音便":     (723, 723),
    "五段・カ行促音便ユク": (739, 739),
    "五段・ガ行":           (723, 723),
    "五段・サ行":           (837, 837),
    "五段・タ行":           (837, 837),
    "五段・ナ行":           (837, 837),
    "五段・バ行":           (837, 837),
    "五段・マ行":           (837, 837),
    "五段・ラ行":           (791, 791),
    "五段・ラ行特殊":       (791, 791),
    "五段・ワ行ウ音便":     (799, 799),
    "五段・ワ行促音便":     (813, 813),
    "一段":                 (680, 680),
    "一段・クレル":         (713, 713),
    "一段・得ル":           (719, 719),
    "サ変":                 (621, 621),
    "サ変・スル":           (633, 633),
    "サ変・−スル":          (633, 633),
    "カ変・クル":           (590, 590),
    "カ変・来ル":           (609, 609),
    "ラ変":                 (642, 642),
    "四段・タ行":           (845, 845),
    "文語":                 (852, 852),
}

POS_MAP = {
    ("名詞", "固有名詞", "一般"):     (1921, 1921),
    ("名詞", "固有名詞", "人名"):     (1922, 1922),
    ("名詞", "固有名詞", "地域"):     (1925, 1925),
    ("名詞", "固有名詞", "組織"):     (1930, 1930),
    ("名詞", "サ変接続", ""):         (1842, 1842),
    ("名詞", "一般", ""):             (1852, 1852),
    ("名詞", "形容動詞語幹", ""):     (1932, 1932),
    ("動詞", "自立", ""):             (837, 837),
    ("形容詞", "自立", ""):           (2424, 2424),
    ("形容詞", "接尾", ""):           (2287, 2287),
    ("副詞", "一般", ""):             (12, 12),
    ("感動詞", "", ""):               (1, 1),
}

POS1_FALLBACK = {
    "名詞":   (1852, 1852),
    "動詞":   (837, 837),
    "形容詞": (2424, 2424),
    "副詞":   (12, 12),
    "感動詞": (1, 1),
}

DEFAULT_IDS = (1852, 1852)


def map_pos(pos1, pos2, pos3, conj_type=""):
    if pos1 == "動詞" and conj_type and conj_type in VERB_CONJ_MAP:
        return VERB_CONJ_MAP[conj_type]
    key3 = (pos1, pos2, pos3)
    if key3 in POS_MAP:
        return POS_MAP[key3]
    key2 = (pos1, pos2, "")
    if key2 in POS_MAP:
        return POS_MAP[key2]
    if pos1 in POS1_FALLBACK:
        return POS1_FALLBACK[pos1]
    return DEFAULT_IDS


def merge_neologd(entries):
    """Download and merge NEologd with fixed POS mapping. Returns (new_count, verb_stats)."""
    print("\n=== Step 1: NEologd merge (fixed POS) ===", file=sys.stderr)
    total_parsed = 0
    new_count = 0
    dup_count = 0
    verb_conj_stats = {}
    pos_stats = {}

    for fname in SEED_FILES:
        url = f"{NEOLOGD_BASE}/{fname}"
        try:
            compressed = download_url(url, fname)
            text = lzma.decompress(compressed).decode("utf-8", errors="replace")
        except Exception as e:
            print(f"  ERROR: {fname}: {e}", file=sys.stderr)
            continue

        file_new = 0
        for line in text.split("\n"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                fields = next(csv.reader([line]))
            except Exception:
                continue
            if len(fields) < 12:
                continue

            surface = fields[0].strip()
            try:
                original_cost = int(fields[3])
            except ValueError:
                original_cost = 6000

            pos1 = fields[4].strip() if len(fields) > 4 else ""
            pos2 = fields[5].strip() if len(fields) > 5 else ""
            pos3 = fields[6].strip() if len(fields) > 6 else ""
            conj_type = fields[8].strip() if len(fields) > 8 else ""

            reading_kata = fields[11].strip() if len(fields) > 11 else ""
            if not reading_kata or not surface:
                continue

            reading = katakana_to_hiragana(reading_kata)
            lid, rid = map_pos(pos1, pos2, pos3, conj_type)
            cost = max(1, min(original_cost + 500, 30000))

            total_parsed += 1
            pos_stats[lid] = pos_stats.get(lid, 0) + 1
            if pos1 == "動詞" and conj_type:
                verb_conj_stats[conj_type] = verb_conj_stats.get(conj_type, 0) + 1

            key = (reading, surface)
            if key in entries:
                dup_count += 1
                continue
            entries[key] = (lid, rid, cost)
            new_count += 1
            file_new += 1

        print(f"    {fname}: +{file_new} new", file=sys.stderr)

    print(f"  NEologd: {total_parsed:,} parsed, {new_count:,} new, {dup_count:,} dups", file=sys.stderr)
    print(f"  POS ID distribution:", file=sys.stderr)
    for pid, count in sorted(pos_stats.items(), key=lambda x: -x[1])[:10]:
        print(f"    ID {pid}: {count:,}", file=sys.stderr)
    if verb_conj_stats:
        print(f"  Verb conjugation types:", file=sys.stderr)
        for ct, count in sorted(verb_conj_stats.items(), key=lambda x: -x[1]):
            mid = VERB_CONJ_MAP.get(ct, ("???",))[0]
            print(f"    {ct}: {count:,} -> Mozc ID {mid}", file=sys.stderr)

    return new_count, verb_conj_stats


# ============================================================
# STEP 2: Wikipedia Japanese article titles
# ============================================================

JAWIKI_URL = "https://dumps.wikimedia.org/jawiki/latest/jawiki-latest-all-titles-in-ns0.gz"

# Place-like suffixes
PLACE_SUFFIXES = re.compile(r'(県|市|町|村|区|駅|山|川|湖|島|峠|港|橋|寺|神社|城)$')
# Person-like patterns
PERSON_PATTERNS = re.compile(r'(氏|太郎|花子|一郎|次郎|三郎|子$)')


def merge_wikipedia_titles(entries):
    """Download jawiki titles and merge as proper nouns.

    Reading strategy:
    1. Exact match: title already exists as a surface in the dict
    2. Sub-word: try splitting title into known words and concatenate readings
    3. Skip if no reading can be determined
    """
    print("\n=== Step 2: Wikipedia Japanese titles ===", file=sys.stderr)

    # Build surface->reading lookup from existing entries (prefer lowest cost)
    print("  Building surface->reading lookup...", file=sys.stderr)
    surface_to_reading = {}
    for (reading, surface), (lid, rid, cost) in entries.items():
        if surface not in surface_to_reading or cost < surface_to_reading[surface][1]:
            surface_to_reading[surface] = (reading, cost)

    def try_compose_reading(title):
        """Try to compose a reading by splitting into known sub-words.
        Uses greedy longest-match from left to right."""
        result = []
        pos = 0
        while pos < len(title):
            found = False
            # Try longest match first (up to 10 chars)
            for length in range(min(10, len(title) - pos), 0, -1):
                substr = title[pos:pos + length]
                if substr in surface_to_reading:
                    result.append(surface_to_reading[substr][0])
                    pos += length
                    found = True
                    break
            if not found:
                # Single char: if hiragana/katakana, use directly
                ch = title[pos]
                cp = ord(ch)
                if 0x3040 <= cp <= 0x309F:  # hiragana
                    result.append(ch)
                elif 0x30A1 <= cp <= 0x30F6:  # katakana
                    result.append(chr(cp - 0x60))
                else:
                    return None  # Can't determine reading
                pos += 1
        return "".join(result)

    # Download jawiki titles
    try:
        data = download_url(JAWIKI_URL, "jawiki-latest-all-titles-in-ns0.gz", timeout=600)
    except Exception as e:
        print(f"  ERROR downloading Wikipedia titles: {e}", file=sys.stderr)
        return 0

    import gzip as gz_mod
    text = gz_mod.decompress(data).decode("utf-8", errors="replace")
    lines = text.strip().split("\n")
    print(f"  Total Wikipedia titles: {len(lines):,}", file=sys.stderr)

    new_count = 0
    skip_no_kanji = 0
    skip_no_reading = 0
    skip_too_long = 0
    skip_dup = 0
    place_count = 0
    person_count = 0
    composed_count = 0

    for title in lines:
        title = title.strip()
        if not title:
            continue

        # Decode URL encoding (Wikipedia titles use _ for spaces)
        title = title.replace("_", " ").strip()

        # Skip titles without kanji
        if not has_kanji(title):
            skip_no_kanji += 1
            continue

        # Skip very long titles
        if len(title) > 15:
            skip_too_long += 1
            continue

        # Skip titles with parenthetical disambiguation or special chars
        if '(' in title or '（' in title or '/' in title:
            skip_too_long += 1
            continue

        # Try to find reading
        reading = None
        if title in surface_to_reading:
            reading = surface_to_reading[title][0]
        else:
            # Try sub-word composition
            reading = try_compose_reading(title)
            if reading:
                composed_count += 1

        if not reading:
            skip_no_reading += 1
            continue

        # Validate reading is all hiragana
        if not is_all_hiragana(reading):
            skip_no_reading += 1
            continue

        # Determine POS
        if PLACE_SUFFIXES.search(title):
            lid, rid = 1925, 1925
            place_count += 1
        elif PERSON_PATTERNS.search(title):
            lid, rid = 1922, 1922
            person_count += 1
        else:
            lid, rid = 1921, 1921

        cost = 5000
        key = (reading, title)
        if key in entries:
            skip_dup += 1
            continue

        entries[key] = (lid, rid, cost)
        new_count += 1

    print(f"  Wikipedia results:", file=sys.stderr)
    print(f"    Added: {new_count:,}", file=sys.stderr)
    print(f"    - via exact match: {new_count - composed_count:,}", file=sys.stderr)
    print(f"    - via sub-word composition: {composed_count:,}", file=sys.stderr)
    print(f"    Skipped (no kanji): {skip_no_kanji:,}", file=sys.stderr)
    print(f"    Skipped (no reading): {skip_no_reading:,}", file=sys.stderr)
    print(f"    Skipped (too long/disambig): {skip_too_long:,}", file=sys.stderr)
    print(f"    Skipped (duplicate): {skip_dup:,}", file=sys.stderr)
    print(f"    Place names: {place_count:,}", file=sys.stderr)
    print(f"    Person names: {person_count:,}", file=sys.stderr)
    return new_count


# ============================================================
# STEP 3: Mozc emoji data
# ============================================================

EMOJI_URL = "https://raw.githubusercontent.com/google/mozc/master/src/data/emoji/emoji_data.tsv"


def merge_emoji(entries):
    """Download Mozc emoji data and merge.

    Format (from file comments):
      1) unicode code point
      2) actual emoji (utf-8)
      3) space-separated readings (hiragana)
      4) unicode name
      5) Japanese name
      6) space-separated descriptions
      7) unicode emoji version
    """
    print("\n=== Step 3: Mozc emoji data ===", file=sys.stderr)
    try:
        data = download_url(EMOJI_URL, "emoji_data.tsv")
        text = data.decode("utf-8", errors="replace")
    except Exception as e:
        print(f"  ERROR downloading emoji data: {e}", file=sys.stderr)
        return 0

    new_count = 0
    total_lines = 0
    for line in text.split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        total_lines += 1

        parts = line.split("\t")
        if len(parts) < 3:
            continue

        # Column 1 (index 1) = emoji character, Column 2 (index 2) = readings
        emoji_char = parts[1].strip()
        readings_field = parts[2].strip()

        if not emoji_char or not readings_field:
            continue

        # Readings are space-separated, may include non-hiragana tokens
        for r in readings_field.split():
            r = r.strip()
            if not r:
                continue
            # Filter: only keep hiragana readings (skip numbers, symbols, etc.)
            if not is_all_hiragana(r):
                continue
            # Skip very short readings (single char like #, *, numbers)
            if len(r) < 2:
                continue
            key = (r, emoji_char)
            if key not in entries:
                entries[key] = (1852, 1852, 5500)  # 名詞,一般, medium cost
                new_count += 1

    print(f"  Emoji: {total_lines} lines, {new_count} new entries", file=sys.stderr)
    return new_count


# ============================================================
# STEP 4: Station names (ekidata.jp) - best effort
# ============================================================

def merge_stations(entries):
    """Extract station names from existing dict entries and ensure X駅 variants exist."""
    print("\n=== Step 4: Station names (from existing dict) ===", file=sys.stderr)

    # Since ekidata.jp URLs are not available, extract station-like entries
    # from the existing dictionary and ensure both "X" and "X駅" forms exist
    station_suffix = "駅"
    new_count = 0

    # Collect station entries: surfaces ending with 駅
    station_readings = {}  # base_name -> reading
    for (reading, surface), (lid, rid, cost) in list(entries.items()):
        if surface.endswith(station_suffix) and len(surface) > 1:
            base = surface[:-1]  # Remove 駅
            base_reading = reading
            # Reading for "X駅" is reading of "X" + "えき"
            if reading.endswith("えき"):
                base_reading = reading[:-2]
            if base and base_reading and has_kanji(base):
                station_readings[base] = base_reading

    # Add both forms
    for base, base_reading in station_readings.items():
        # Ensure base name exists
        key_base = (base_reading, base)
        if key_base not in entries:
            entries[key_base] = (1925, 1925, 4500)
            new_count += 1
        # Ensure X駅 exists
        key_eki = (base_reading + "えき", base + station_suffix)
        if key_eki not in entries:
            entries[key_eki] = (1925, 1925, 4500)
            new_count += 1

    print(f"  Station-related: {len(station_readings):,} base stations found, {new_count} new entries", file=sys.stderr)
    return new_count


# ============================================================
# STEP 5: Filter (same logic as filter_dict.py)
# ============================================================

def filter_entries(entries):
    """Apply the same filtering as filter_dict.py."""
    print("\n=== Step 5: Filtering ===", file=sys.stderr)
    before = len(entries)
    to_remove = []
    stats = {
        "drop_long_reading": 0,
        "drop_gt7500": 0,
        "drop_6501_7500": 0,
        "drop_5501_6500": 0,
        "drop_4001_5500": 0,
    }

    for (reading, surface), (lid, rid, cost) in entries.items():
        if len(reading) > 15:
            stats["drop_long_reading"] += 1
            to_remove.append((reading, surface))
            continue
        if cost <= 4000:
            continue
        if cost <= 5500:
            if surface == reading and is_all_hiragana(reading):
                stats["drop_4001_5500"] += 1
                to_remove.append((reading, surface))
            continue
        if cost <= 6500:
            if not has_kanji(surface):
                stats["drop_5501_6500"] += 1
                to_remove.append((reading, surface))
            continue
        if cost <= 7500:
            if not (has_kanji(surface) and len(reading) <= 6):
                stats["drop_6501_7500"] += 1
                to_remove.append((reading, surface))
            continue
        # cost > 7500
        stats["drop_gt7500"] += 1
        to_remove.append((reading, surface))

    for key in to_remove:
        del entries[key]

    after = len(entries)
    print(f"  Before: {before:,}", file=sys.stderr)
    for k, v in stats.items():
        print(f"    {k}: {v:,}", file=sys.stderr)
    print(f"  After: {after:,} (removed {before - after:,})", file=sys.stderr)
    return before - after


# ============================================================
# MAIN
# ============================================================

def main():
    t_start = time.time()
    print("=" * 60, file=sys.stderr)
    print("  Nacre IME Data Pipeline", file=sys.stderr)
    print("=" * 60, file=sys.stderr)

    # Backup
    if os.path.exists(DICT_PATH):
        print(f"\nBacking up {DICT_PATH} -> {BACKUP_PATH}", file=sys.stderr)
        shutil.copy2(DICT_PATH, BACKUP_PATH)

    # Load existing dictionary
    print("\nLoading existing Mozc dictionary...", file=sys.stderr)
    entries = load_mozc_dict(DICT_PATH)
    base_count = len(entries)
    print(f"  Base dictionary: {base_count:,} entries", file=sys.stderr)

    # Step 1: NEologd
    neologd_count, verb_stats = merge_neologd(entries)
    after_neologd = len(entries)
    print(f"  After NEologd: {after_neologd:,} entries", file=sys.stderr)

    # Step 2: Wikipedia
    wiki_count = merge_wikipedia_titles(entries)
    after_wiki = len(entries)
    print(f"  After Wikipedia: {after_wiki:,} entries", file=sys.stderr)

    # Step 3: Emoji
    emoji_count = merge_emoji(entries)
    after_emoji = len(entries)
    print(f"  After emoji: {after_emoji:,} entries", file=sys.stderr)

    # Step 4: Stations
    station_count = merge_stations(entries)
    after_stations = len(entries)
    print(f"  After stations: {after_stations:,} entries", file=sys.stderr)

    # Step 5: Filter
    removed = filter_entries(entries)
    final_count = len(entries)

    # Step 6: Write
    print("\n=== Writing final dictionary ===", file=sys.stderr)
    total, gz_size = write_dict(entries, DICT_PATH)

    t_end = time.time()
    elapsed = t_end - t_start

    print("\n" + "=" * 60, file=sys.stderr)
    print("  SUMMARY", file=sys.stderr)
    print("=" * 60, file=sys.stderr)
    print(f"  Base Mozc:      {base_count:,}", file=sys.stderr)
    print(f"  + NEologd:      +{neologd_count:,}", file=sys.stderr)
    print(f"  + Wikipedia:    +{wiki_count:,}", file=sys.stderr)
    print(f"  + Emoji:        +{emoji_count:,}", file=sys.stderr)
    print(f"  + Stations:     +{station_count:,}", file=sys.stderr)
    print(f"  - Filtered:     -{removed:,}", file=sys.stderr)
    print(f"  ─────────────────────────", file=sys.stderr)
    print(f"  Final entries:  {final_count:,}", file=sys.stderr)
    print(f"  File size:      {gz_size/1024/1024:.1f} MB (gzip)", file=sys.stderr)
    print(f"  Time:           {elapsed:.0f}s", file=sys.stderr)
    print(f"  Output:         {DICT_PATH}", file=sys.stderr)
    print("=" * 60, file=sys.stderr)


if __name__ == "__main__":
    main()

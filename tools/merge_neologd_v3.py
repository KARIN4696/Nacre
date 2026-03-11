#!/usr/bin/env python3
"""
Download NEologd seed data, map MeCab IPA POS → Mozc v3 POS IDs,
merge with existing mozc_dict.bin (Mozc entries take priority).

NEologd CSV format (13 fields):
  surface, left_id, right_id, cost, pos1, pos2, pos3, pos4,
  conj_type, conj_form, base_form, reading, pronunciation

Output format (gzip TSV, same as mozc_dict.bin):
  reading\tsurface\tleft_id\tright_id\tcost
"""

import sys
import os
import gzip
import io
import lzma
import csv
import urllib.request
import re

# --- NEologd seed file URLs ---
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

# --- Mozc v3 POS ID mappings (from id.def) ---
# These IDs come from mozc_raw/id.def in this repo.
# For verbs, we use conj_type (field 8) to select the correct conjugation class.
POS_MAP = {
    # (pos1, pos2, pos3) -> (left_id, right_id)
    ("名詞", "固有名詞", "一般"):     (1921, 1921),
    ("名詞", "固有名詞", "人名"):     (1922, 1922),
    ("名詞", "固有名詞", "地域"):     (1925, 1925),
    ("名詞", "固有名詞", "組織"):     (1930, 1930),
    ("名詞", "サ変接続", ""):         (1842, 1842),
    ("名詞", "一般", ""):             (1852, 1852),
    ("名詞", "形容動詞語幹", ""):     (1932, 1932),
    # Verbs: generic fallback (overridden by VERB_CONJ_MAP below)
    ("動詞", "自立", ""):             (837, 837),   # 五段動詞,基本形,* (generic)
    # Adjectives
    ("形容詞", "自立", ""):           (2424, 2424), # 形容詞,自立,形容詞・アウオ段,基本形,*
    ("形容詞", "接尾", ""):           (2287, 2287), # 形容詞,接尾,形容詞・アウオ段,基本形
    ("副詞", "一般", ""):             (12, 12),
    ("感動詞", "", ""):               (1, 1),       # その他,間投
}

# --- Verb conjugation type → Mozc ID mapping ---
# Maps NEologd conj_type (field 8) to Mozc 動詞,自立,*,*,<type>,基本形,* IDs.
# Source: mozc_raw/id.def
VERB_CONJ_MAP = {
    # 五段 (godan) verbs - mapped to closest Mozc 自立 ID
    "五段・カ行イ音便":     (723, 723),   # 動詞,自立,五段・カ行イ音便,基本形,*
    "五段・カ行促音便":     (723, 723),   # → イ音便 fallback (行く is special)
    "五段・カ行促音便ユク": (739, 739),   # 動詞,自立,五段・カ行促音便ユク,基本形,*
    "五段・ガ行":           (723, 723),   # → カ行イ音便 (closest godan, same euphony group)
    "五段・サ行":           (837, 837),   # → 五段動詞,基本形,* (generic godan)
    "五段・タ行":           (837, 837),   # → 五段動詞,基本形,*
    "五段・ナ行":           (837, 837),   # → 五段動詞,基本形,*
    "五段・バ行":           (837, 837),   # → 五段動詞,基本形,*
    "五段・マ行":           (837, 837),   # → 五段動詞,基本形,*
    "五段・ラ行":           (791, 791),   # 動詞,自立,五段・ラ行特殊,基本形,* (closest ラ行)
    "五段・ラ行特殊":       (791, 791),   # 動詞,自立,五段・ラ行特殊,基本形,*
    "五段・ワ行ウ音便":     (799, 799),   # 動詞,自立,五段・ワ行ウ音便,基本形,*
    "五段・ワ行促音便":     (813, 813),   # 動詞,自立,五段・ワ行促音便,基本形,*
    # 一段 (ichidan) verbs
    "一段":                 (680, 680),   # 動詞,自立,一段,基本形,*
    "一段・クレル":         (713, 713),   # 動詞,自立,一段・クレル,基本形,*
    "一段・得ル":           (719, 719),   # 動詞,自立,一段・得ル,基本形,*
    # サ変 (sa-hen) verbs
    "サ変":                 (621, 621),   # 動詞,自立,サ変,基本形,*
    "サ変・スル":           (633, 633),   # 動詞,自立,サ変・スル,基本形,*
    "サ変・−スル":          (633, 633),   # variant spelling → same as スル
    # カ変 (ka-hen) verbs
    "カ変・クル":           (590, 590),   # 動詞,自立,カ変・クル,基本形,*
    "カ変・来ル":           (609, 609),   # 動詞,自立,カ変・来ル,基本形,*
    # Others
    "ラ変":                 (642, 642),   # 動詞,自立,ラ変,基本形,*
    "四段・タ行":           (845, 845),   # 動詞,自立,四段・タ行,基本形,*
    "文語":                 (852, 852),   # 動詞,自立,文語,基本形,*
}

# Fallback by pos1 only
POS1_FALLBACK = {
    "名詞":   (1852, 1852),   # 名詞,一般
    "動詞":   (837, 837),     # 五段動詞,基本形,* (generic godan)
    "形容詞": (2424, 2424),   # 形容詞,自立,形容詞・アウオ段,基本形,*
    "副詞":   (12, 12),       # 副詞,一般
    "感動詞": (1, 1),
}

DEFAULT_IDS = (1852, 1852)  # 名詞,一般 as ultimate fallback


def katakana_to_hiragana(text):
    """Convert katakana reading to hiragana (Mozc dict uses hiragana)."""
    result = []
    for ch in text:
        cp = ord(ch)
        # Katakana U+30A1..U+30F6 → Hiragana U+3041..U+3096
        if 0x30A1 <= cp <= 0x30F6:
            result.append(chr(cp - 0x60))
        # Katakana U+30F7..U+30FA (ヷヸヹヺ) have no direct hiragana
        # Keep as-is or approximate
        else:
            result.append(ch)
    return "".join(result)


def map_pos_to_mozc_ids(pos1, pos2, pos3, conj_type=""):
    """Map MeCab IPA POS fields to Mozc left_id/right_id.

    For verbs, uses conj_type to select the correct conjugation class ID.
    This is critical for the Mozc connection matrix to work properly
    with verb conjugations.
    """
    # For verbs, use conj_type for finer-grained mapping
    if pos1 == "動詞" and conj_type:
        if conj_type in VERB_CONJ_MAP:
            return VERB_CONJ_MAP[conj_type]

    # Try exact match (pos1, pos2, pos3)
    key3 = (pos1, pos2, pos3)
    if key3 in POS_MAP:
        return POS_MAP[key3]

    # Try (pos1, pos2, "")
    key2 = (pos1, pos2, "")
    if key2 in POS_MAP:
        return POS_MAP[key2]

    # Try pos1 fallback
    if pos1 in POS1_FALLBACK:
        return POS1_FALLBACK[pos1]

    return DEFAULT_IDS


def download_xz(url, desc=""):
    """Download and decompress an .xz file, return text."""
    print(f"  Downloading {desc or url}...", file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "Nacre/1.0"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        compressed = resp.read()
    decompressed = lzma.decompress(compressed)
    return decompressed.decode("utf-8", errors="replace")


def parse_neologd_csv(text):
    """Parse NEologd CSV and yield (reading, surface, left_id, right_id, cost, pos1, conj_type)."""
    for line in text.split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue

        # CSV format: surface,left_id,right_id,cost,pos1,pos2,pos3,pos4,conj1,conj2,base,reading,pronunciation
        # Some fields may contain commas inside quotes, so use csv reader
        try:
            fields = next(csv.reader([line]))
        except Exception:
            continue

        if len(fields) < 12:
            continue

        surface = fields[0].strip()
        # NEologd left_id/right_id are MeCab IPA IDs, NOT Mozc IDs - we ignore them
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

        # Convert katakana reading to hiragana
        reading = katakana_to_hiragana(reading_kata)

        # Map POS to Mozc IDs (using conj_type for verb conjugation classes)
        left_id, right_id = map_pos_to_mozc_ids(pos1, pos2, pos3, conj_type)

        # Adjust cost: NEologd costs are in MeCab scale.
        # MeCab and Mozc both use similar cost scales, but NEologd entries
        # tend to have very low costs (-1 to ~8000). We keep the original
        # cost but clamp to a reasonable range and add a small penalty
        # so Mozc's own entries are preferred for duplicates.
        cost = max(1, min(original_cost + 500, 30000))

        yield (reading, surface, left_id, right_id, cost, pos1, conj_type)


def load_mozc_dict(path):
    """Load existing gzipped mozc_dict.bin, return dict {(reading,surface): (lid,rid,cost)}."""
    entries = {}
    if not os.path.exists(path):
        print(f"  Warning: {path} not found, starting fresh", file=sys.stderr)
        return entries

    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) < 5:
                continue
            reading = parts[0]
            surface = parts[1]
            lid = int(parts[2])
            rid = int(parts[3])
            cost = int(parts[4])
            key = (reading, surface)
            if key not in entries or cost < entries[key][2]:
                entries[key] = (lid, rid, cost)

    return entries


def main():
    output_dir = sys.argv[1] if len(sys.argv) > 1 else "ime-core/src/main/assets/dict"
    dict_path = os.path.join(output_dir, "mozc_dict.bin")

    print("=== NEologd + Mozc v3 Merge ===", file=sys.stderr)

    # 1. Load existing Mozc dictionary
    print("\n[1/3] Loading existing Mozc dictionary...", file=sys.stderr)
    mozc_entries = load_mozc_dict(dict_path)
    mozc_count = len(mozc_entries)
    print(f"  Loaded {mozc_count} Mozc entries", file=sys.stderr)

    # 2. Download and parse NEologd seed files
    print("\n[2/3] Downloading NEologd seed files...", file=sys.stderr)
    neologd_total = 0
    neologd_new = 0
    neologd_skipped_dup = 0
    pos_stats = {}
    verb_conj_stats = {}  # Track verb conjugation type distribution

    for fname in SEED_FILES:
        url = f"{NEOLOGD_BASE}/{fname}"
        try:
            text = download_xz(url, fname)
        except Exception as e:
            print(f"  ERROR downloading {fname}: {e}", file=sys.stderr)
            continue

        file_count = 0
        file_new = 0
        for reading, surface, lid, rid, cost, pos1, conj_type in parse_neologd_csv(text):
            neologd_total += 1
            file_count += 1
            key = (reading, surface)

            # Track POS distribution
            pos_stats[lid] = pos_stats.get(lid, 0) + 1
            if pos1 == "動詞" and conj_type:
                verb_conj_stats[conj_type] = verb_conj_stats.get(conj_type, 0) + 1

            if key in mozc_entries:
                # Mozc takes priority - only replace if Mozc doesn't have it
                neologd_skipped_dup += 1
                continue

            mozc_entries[key] = (lid, rid, cost)
            neologd_new += 1
            file_new += 1

        print(f"    {fname}: {file_count} parsed, {file_new} new", file=sys.stderr)

    print(f"\n  NEologd total parsed: {neologd_total}", file=sys.stderr)
    print(f"  NEologd new (added): {neologd_new}", file=sys.stderr)
    print(f"  NEologd duplicates (Mozc kept): {neologd_skipped_dup}", file=sys.stderr)

    # Show POS distribution
    print(f"\n  POS ID distribution (NEologd entries):", file=sys.stderr)
    for pid, count in sorted(pos_stats.items(), key=lambda x: -x[1])[:15]:
        print(f"    ID {pid}: {count}", file=sys.stderr)

    # Show verb conjugation type distribution
    if verb_conj_stats:
        print(f"\n  Verb conjugation type distribution:", file=sys.stderr)
        for ctype, count in sorted(verb_conj_stats.items(), key=lambda x: -x[1]):
            mapped = VERB_CONJ_MAP.get(ctype, ("???", "???"))
            print(f"    {ctype}: {count} -> Mozc ID {mapped[0]}", file=sys.stderr)

    # 3. Write merged dictionary
    print(f"\n[3/3] Writing merged dictionary...", file=sys.stderr)
    sorted_entries = sorted(
        ((r, s, lid, rid, c) for (r, s), (lid, rid, c) in mozc_entries.items()),
        key=lambda x: (x[0], x[4])
    )

    total = len(sorted_entries)
    print(f"  Total entries: {total} (Mozc: {mozc_count}, NEologd new: {neologd_new})", file=sys.stderr)

    buf = io.BytesIO()
    for r, s, lid, rid, c in sorted_entries:
        buf.write(f"{r}\t{s}\t{lid}\t{rid}\t{c}\n".encode("utf-8"))

    raw_size = buf.tell()
    buf.seek(0)

    os.makedirs(output_dir, exist_ok=True)
    with gzip.open(dict_path, "wb", compresslevel=9) as f:
        f.write(buf.read())

    gz_size = os.path.getsize(dict_path)
    print(f"  Raw: {raw_size / 1024 / 1024:.1f}MB → Gzip: {gz_size / 1024 / 1024:.1f}MB", file=sys.stderr)
    print(f"\n=== Done: {dict_path} ({total} entries) ===", file=sys.stderr)


if __name__ == "__main__":
    main()

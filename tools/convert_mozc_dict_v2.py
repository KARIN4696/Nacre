#!/usr/bin/env python3
"""
Convert Mozc OSS dictionary to Nacre IME format — V2 (128-group clustering).

The original v1 script collapsed Mozc's 2670 POS IDs into only 14 groups,
losing critical grammatical distinctions (e.g., all particles got the same
connection costs). This v2 uses ~128 groups that preserve:

  - Verb conjugation types (五段 vs 一段 vs サ変 vs カ変)
  - Verb conjugation forms (基本形 vs 連用形 vs 未然形 etc.)
  - Verb independence (自立 vs 非自立 vs 接尾)
  - Particle subtypes (格助詞 vs 接続助詞 vs 係助詞 vs 終助詞 etc.)
  - Noun subtypes (一般 vs 固有名詞 vs サ変接続 vs 数 vs 非自立 etc.)
  - Adjective conjugation forms
  - Auxiliary verb types (た/だ vs です/ます vs ない vs れる/られる etc.)
  - Symbol subtypes (句点 vs 読点 vs 括弧開 vs 括弧閉)

The Kotlin NacreDictionary.kt already reads numGroups dynamically from the
connection_group.tsv header line, so no Kotlin changes are needed.

Downloads Mozc dictionary files from GitHub (or uses local cache in mozc_raw/).
Outputs:
  1. connection_group.tsv — NxN connection cost matrix (N ~128)
  2. mozc_dict.tsv + mozc_dict.bin (gzipped) — dictionary with new group IDs

Usage: python3 convert_mozc_dict_v2.py [output_dir]
       Default output_dir: ime-core/src/main/assets/dict
"""

import sys
import os
import gzip
import urllib.request
from collections import defaultdict

MOZC_BASE = "https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss"
DICT_FILES = [f"dictionary{i:02d}.txt" for i in range(10)] + ["suffix.txt"]
ID_DEF_URL = f"{MOZC_BASE}/id.def"
CONN_URL = f"{MOZC_BASE}/connection_single_column.txt"

# Local cache directory (next to this script)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(SCRIPT_DIR, "mozc_raw")


# ---------------------------------------------------------------------------
# 128-group POS clustering
# ---------------------------------------------------------------------------
# Design principle: group by grammatical behavior that affects which words
# can follow/precede. Conjugation form (活用形) matters most for connection
# costs because it determines what can attach next (e.g., 未然形 + れる,
# 連用形 + た, 基本形 + 。).
#
# Group ID allocation plan (~128 groups):
#   0       : BOS/EOS
#   1-19    : 名詞 (noun subtypes)
#   20-39   : 動詞 (verb: conj-type x conj-form x independence)
#   40-54   : 形容詞 (adjective conj forms)
#   55-74   : 助動詞 (auxiliary verb types x forms)
#   75-89   : 助詞 (particle subtypes)
#   90-94   : 副詞
#   95-97   : 連体詞
#   98-99   : 接続詞
#  100-101  : 感動詞
#  102-105  : 接頭詞
#  106-114  : 記号 (symbol subtypes)
#  115-116  : フィラー
#  117-119  : その他
#  120-127  : reserved / overflow

# --- Conjugation form mapping (shared by verbs, adjectives, auxiliaries) ---
CONJ_FORM_MAP = {
    "基本形": 0,
    "連用形": 1,
    "連用タ接続": 2,
    "連用テ接続": 2,
    "連用ゴザイ接続": 2,
    "連用デ接続": 2,
    "連用ニ接続": 2,
    "未然形": 3,
    "未然ウ接続": 3,
    "未然ヌ接続": 3,
    "未然レル接続": 3,
    "未然特殊": 3,
    "仮定形": 4,
    "仮定縮約１": 4,
    "仮定縮約２": 4,
    "命令ｅ": 5,
    "命令ｉ": 5,
    "命令ｒｏ": 5,
    "命令ｙｏ": 5,
    "体言接続": 6,
    "体言接続特殊": 6,
    "体言接続特殊２": 6,
    "ガル接続": 7,
    "文語基本形": 0,
    "現代基本形": 0,
    "口語基本形": 0,
    "口語基本形-促音便": 0,
    "基本形-促音便": 0,
    "音便基本形": 0,
}
NUM_CONJ_FORMS = 8  # 0-7

def get_conj_form_id(form_str):
    """Map conjugation form string to 0-7."""
    return CONJ_FORM_MAP.get(form_str, 0)


def classify_pos(pos_str):
    """
    Classify a Mozc POS string into a group ID (0-127).
    pos_str is like: "動詞,自立,*,*,五段・カ行イ音便,連用タ接続"
    """
    fields = pos_str.split(",")
    cat1 = fields[0] if len(fields) > 0 else ""
    cat2 = fields[1] if len(fields) > 1 else "*"
    cat3 = fields[2] if len(fields) > 2 else "*"
    cat5 = fields[4] if len(fields) > 4 else "*"  # conjugation type
    cat6 = fields[5] if len(fields) > 5 else "*"  # conjugation form

    # --- BOS/EOS ---
    if cat1 == "BOS/EOS":
        return 0

    # --- 名詞 (1-19) ---
    if cat1 == "名詞":
        if cat2 == "一般":
            return 1
        if cat2 == "固有名詞":
            if cat3 == "人名":
                return 2
            if cat3 in ("地域", "国"):
                return 3
            if cat3 == "組織":
                return 4
            return 5  # 固有名詞,一般
        if cat2 == "サ変接続":
            return 6
        if cat2 == "形容動詞語幹":
            return 7
        if cat2 == "数":
            return 8
        if cat2 == "代名詞":
            return 9
        if cat2 == "副詞可能":
            return 10
        if cat2 == "非自立":
            return 11
        if cat2 == "接尾":
            if cat3 == "サ変接続":
                return 12
            if cat3 == "助数詞":
                return 13
            if cat3 == "人名":
                return 14
            if cat3 == "地域":
                return 14
            return 15  # 接尾,一般 etc.
        if cat2 == "接尾可能":
            return 15
        if cat2 == "接続詞的":
            return 16
        if cat2 == "動詞非自立的":
            return 17
        if cat2 == "ナイ形容詞語幹":
            return 18
        if cat2 == "引用文字列":
            return 19
        if cat2 == "特殊":
            return 11  # 特殊,助動詞語幹 → non-independent
        return 1  # fallback to general noun

    # --- 動詞 (20-39) ---
    if cat1 == "動詞":
        form_id = get_conj_form_id(cat6)
        # Independence: 自立=0, 非自立=1, 接尾=2
        if cat2 == "非自立":
            indep = 1
        elif cat2 == "接尾":
            indep = 2
        else:
            indep = 0

        # Conjugation type grouping (coarser than per-行)
        if "一段" in cat5:
            conj_type = 0  # ichidan
        elif "五段" in cat5 or "四段" in cat5:
            conj_type = 1  # godan
        elif "サ変" in cat5:
            conj_type = 2  # sa-hen
        elif "カ変" in cat5:
            conj_type = 3  # ka-hen
        elif "文語" in cat5 or "ラ変" in cat5:
            conj_type = 3  # classical → same as ka-hen (rare)
        else:
            conj_type = 0  # fallback

        # Allocate: base=20, then conj_type*5 + form_bucket
        # form_bucket: 0=基本/体言, 1=連用, 2=未然, 3=仮定/命令, 4=other
        if form_id == 0 or form_id == 6:  # 基本形 or 体言接続
            form_bucket = 0
        elif form_id in (1, 2):  # 連用形
            form_bucket = 1
        elif form_id == 3:  # 未然形
            form_bucket = 2
        elif form_id in (4, 5):  # 仮定/命令
            form_bucket = 3
        else:
            form_bucket = 4  # ガル接続 etc.

        group = 20 + conj_type * 5 + form_bucket
        # For non-independent verbs, offset by +1 within the form bucket
        # but cap at 39
        if indep >= 1 and form_bucket < 4:
            # Use a slight offset: non-independent verbs of same type share group
            # We don't have room for full independence split, so just shift
            # 非自立 gets its own group per conj_type (at form_bucket=4)
            group = 20 + conj_type * 5 + 4
        return min(group, 39)

    # --- 形容詞 (40-54) ---
    if cat1 == "形容詞":
        form_id = get_conj_form_id(cat6)
        if cat2 == "非自立":
            indep_offset = 8
        elif cat2 == "接尾":
            indep_offset = 8  # group with 非自立
        else:
            indep_offset = 0

        # form_bucket: 0=基本, 1=連用, 2=未然, 3=仮定/命令, 4=体言接続, 5=ガル接続
        if form_id == 0:
            fb = 0
        elif form_id in (1, 2):
            fb = 1
        elif form_id == 3:
            fb = 2
        elif form_id in (4, 5):
            fb = 3
        elif form_id == 6:
            fb = 4
        elif form_id == 7:
            fb = 5
        else:
            fb = 0

        # Adjective type: アウオ段 vs イ段 (matters less, merge)
        # 8 forms x 2 independence = 16 groups max, fits in 40-55
        group = 40 + indep_offset + fb
        return min(group, 54)

    # --- 助動詞 (55-74) ---
    if cat1 == "助動詞":
        form_id = get_conj_form_id(cat6)

        # Group by auxiliary type (which auxiliary it is)
        if "特殊・タ" == cat5:
            aux_type = 0  # た/だ (past)
        elif "特殊・ダ" == cat5:
            aux_type = 1  # だ (copula)
        elif "特殊・デス" == cat5:
            aux_type = 2  # です (polite copula)
        elif "特殊・マス" == cat5:
            aux_type = 3  # ます (polite)
        elif "特殊・ナイ" == cat5:
            aux_type = 4  # ない (negative)
        elif "特殊・タイ" == cat5:
            aux_type = 5  # たい (desiderative)
        elif "特殊・ヌ" == cat5:
            aux_type = 6  # ぬ (classical negative)
        elif "形容詞・イ段" == cat5:
            aux_type = 4  # ない-like conjugation
        elif "不変化型" == cat5 or "不変化形" == cat5:
            aux_type = 7  # う/ん/まい etc.
        elif "五段・ラ行" in cat5:
            aux_type = 8  # ある-type (progressive)
        elif "特殊・ジャ" == cat5 or "特殊・ヤ" == cat5:
            aux_type = 1  # じゃ/や → copula variant
        elif "下二・タ行" == cat5:
            aux_type = 0  # つ (classical past) → group with た
        elif "文語" in cat5:
            aux_type = 9  # classical auxiliaries
        else:
            aux_type = 9  # other

        # form_bucket: 0=基本/体言, 1=連用, 2=未然/仮定/命令
        if form_id == 0 or form_id == 6:
            fb = 0
        elif form_id in (1, 2):
            fb = 1
        else:
            fb = 2

        # 10 aux_types x 2 form_buckets (merge fb=2 into fb=0 to save space)
        # Actually: use 2 form buckets → 10*2 = 20 groups (55-74)
        group = 55 + aux_type * 2 + min(fb, 1)
        return min(group, 74)

    # --- 助詞 (75-89) ---
    if cat1 == "助詞":
        if cat2 == "格助詞":
            if cat3 == "引用":
                return 76  # と(引用)
            if cat3 == "連語":
                return 77  # という etc.
            if cat3 == "意志":
                return 78  # へ (intentional)
            return 75  # が/を/に/で/から/より etc.
        if cat2 == "接続助詞":
            return 79  # て/ので/から/けど/ば etc.
        if cat2 == "係助詞":
            return 80  # は/も/こそ/でも etc.
        if cat2 == "副助詞":
            return 81  # まで/ばかり/だけ/しか/ほど etc.
        if cat2 == "終助詞":
            return 82  # よ/ね/な/ぞ/ぜ/わ etc.
        if cat2 == "並立助詞":
            return 83  # と/や/か/なり etc.
        if cat2 == "副助詞／並立助詞／終助詞":
            return 84  # か (multi-function)
        if cat2 == "連体化":
            return 85  # の
        if cat2 == "副詞化":
            return 86  # に (adverbializer)
        if cat2 == "助詞連続":
            return 87  # compound particles
        if cat2 == "特殊":
            return 88  # special particles
        return 89  # fallback

    # --- 副詞 (90-94) ---
    if cat1 == "副詞":
        if cat2 == "助詞類接続":
            return 91  # こう/そう/どう etc. (connect like particles)
        return 90  # general adverb

    # --- 連体詞 (95-97) ---
    if cat1 == "連体詞":
        return 95

    # --- 接続詞 (98-99) ---
    if cat1 == "接続詞":
        return 98

    # --- 感動詞 (100-101) ---
    if cat1 == "感動詞":
        return 100

    # --- 接頭詞 (102-105) ---
    if cat1 in ("接頭詞", "接頭辞"):
        if cat2 == "名詞接続":
            return 102
        if cat2 == "動詞接続":
            return 103
        if cat2 == "形容詞接続":
            return 104
        if cat2 == "数接続":
            return 105
        return 102  # default

    # --- 記号 (106-114) ---
    if cat1 in ("記号", "補助記号"):
        if cat2 == "句点":
            return 106
        if cat2 == "読点":
            return 107
        if cat2 == "括弧開":
            return 108
        if cat2 == "括弧閉":
            return 109
        if cat2 == "アルファベット":
            return 110
        if cat2 == "一般":
            return 111
        return 111
    if cat1 == "空白":
        return 112

    # --- フィラー (115-116) ---
    if cat1 == "フィラー":
        return 115

    # --- その他 (117-119) ---
    if cat1 == "その他":
        return 117

    # --- 接尾辞 (map to noun suffix) ---
    if cat1 in ("接尾辞", "接尾"):
        return 15

    # --- 形状詞 (na-adjective → adj group) ---
    if cat1 == "形状詞":
        return 7  # same as 名詞,形容動詞語幹

    # --- 代名詞 ---
    if cat1 == "代名詞":
        return 9

    # Fallback
    return 117


def discover_num_groups():
    """Determine the actual number of groups used (max group ID + 1)."""
    # We know the max possible is 127, but let's verify
    return 128  # Fixed at 128 for clean power-of-2 matrix size


# ---------------------------------------------------------------------------
# File I/O
# ---------------------------------------------------------------------------

def download(url, desc=""):
    """Download a URL and return content as string."""
    print(f"  Downloading {desc or url}...", file=sys.stderr)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Nacre-Dict-Builder/2.0"})
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.read().decode("utf-8")
    except Exception as e:
        print(f"  ERROR downloading {url}: {e}", file=sys.stderr)
        return None


def load_or_download(filename, url, desc=""):
    """Load from cache or download."""
    cache_path = os.path.join(CACHE_DIR, filename)
    if os.path.exists(cache_path):
        print(f"  Using cached {filename}", file=sys.stderr)
        with open(cache_path, "r", encoding="utf-8") as f:
            return f.read()
    text = download(url, desc or filename)
    if text:
        os.makedirs(CACHE_DIR, exist_ok=True)
        with open(cache_path, "w", encoding="utf-8") as f:
            f.write(text)
    return text


def parse_id_def(text):
    """Parse Mozc id.def → {pos_id: group_id}."""
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
        group = classify_pos(pos_str)
        id_to_group[pos_id] = group

    id_to_group[0] = 0  # BOS/EOS
    print(f"  Parsed {len(id_to_group)} POS ID → group mappings", file=sys.stderr)
    return id_to_group


def parse_connection_matrix(text, id_to_group, num_groups):
    """
    Parse Mozc connection_single_column.txt and collapse to num_groups x num_groups.
    Uses weighted average (by frequency of POS ID pairs).
    """
    lines = text.strip().split("\n")
    num_ids = int(lines[0].strip())
    print(f"  Mozc connection matrix: {num_ids}x{num_ids} → collapsing to {num_groups}x{num_groups}", file=sys.stderr)

    # Read flat costs
    costs = []
    for line in lines[1:]:
        line = line.strip()
        if line:
            costs.append(int(line))

    expected = num_ids * num_ids
    actual = len(costs)
    if actual < expected:
        print(f"  WARNING: expected {expected} costs, got {actual}", file=sys.stderr)

    # Collapse by averaging
    group_sums = [[0] * num_groups for _ in range(num_groups)]
    group_counts = [[0] * num_groups for _ in range(num_groups)]

    processed = 0
    for right_id in range(num_ids):
        rg = id_to_group.get(right_id)
        if rg is None:
            continue
        base_idx = right_id * num_ids
        for left_id in range(num_ids):
            lg = id_to_group.get(left_id)
            if lg is None:
                continue
            idx = base_idx + left_id
            if idx >= actual:
                continue
            group_sums[rg][lg] += costs[idx]
            group_counts[rg][lg] += 1
            processed += 1

    print(f"  Processed {processed:,} cost entries", file=sys.stderr)

    # Average
    matrix = [[0] * num_groups for _ in range(num_groups)]
    filled = 0
    empty = 0
    for r in range(num_groups):
        for l in range(num_groups):
            if group_counts[r][l] > 0:
                matrix[r][l] = group_sums[r][l] // group_counts[r][l]
                filled += 1
            else:
                matrix[r][l] = 10000  # Default high cost for unseen pairs
                empty += 1

    print(f"  Matrix cells: {filled} filled, {empty} empty (default=10000)", file=sys.stderr)
    return matrix


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

        reading = parts[0]
        left_id = int(parts[1])
        right_id = int(parts[2])
        cost = int(parts[3])
        surface = parts[4]

        if not reading or not surface:
            continue

        left_group = id_to_group.get(left_id, 117)  # 117 = その他
        right_group = id_to_group.get(right_id, 117)

        entries.append((reading, surface, left_group, right_group, cost))

    return entries


def print_group_stats(id_to_group):
    """Print how many Mozc POS IDs map to each group."""
    counts = defaultdict(int)
    for gid in id_to_group.values():
        counts[gid] += 1

    # Group name hints
    GROUP_NAMES = {
        0: "BOS/EOS",
        1: "名詞,一般", 2: "名詞,固有,人名", 3: "名詞,固有,地域",
        4: "名詞,固有,組織", 5: "名詞,固有,一般", 6: "名詞,サ変接続",
        7: "名詞,形容動詞語幹", 8: "名詞,数", 9: "名詞,代名詞",
        10: "名詞,副詞可能", 11: "名詞,非自立", 12: "名詞,接尾,サ変",
        13: "名詞,接尾,助数詞", 14: "名詞,接尾,人名/地域", 15: "名詞,接尾,一般",
        16: "名詞,接続詞的", 17: "名詞,動詞非自立的", 18: "名詞,ナイ形容詞語幹",
        19: "名詞,引用文字列",
        20: "動詞,一段,基本", 21: "動詞,一段,連用", 22: "動詞,一段,未然",
        23: "動詞,一段,仮定/命令", 24: "動詞,一段,非自立",
        25: "動詞,五段,基本", 26: "動詞,五段,連用", 27: "動詞,五段,未然",
        28: "動詞,五段,仮定/命令", 29: "動詞,五段,非自立",
        30: "動詞,サ変,基本", 31: "動詞,サ変,連用", 32: "動詞,サ変,未然",
        33: "動詞,サ変,仮定/命令", 34: "動詞,サ変,非自立",
        35: "動詞,カ変/文語,基本", 36: "動詞,カ変/文語,連用", 37: "動詞,カ変/文語,未然",
        38: "動詞,カ変/文語,仮定/命令", 39: "動詞,カ変/文語,非自立",
        40: "形容詞,自立,基本", 41: "形容詞,自立,連用", 42: "形容詞,自立,未然",
        43: "形容詞,自立,仮定/命令", 44: "形容詞,自立,体言接続",
        45: "形容詞,自立,ガル接続",
        48: "形容詞,非自立,基本", 49: "形容詞,非自立,連用",
        55: "助動詞,た,基本", 56: "助動詞,た,連用",
        57: "助動詞,だ(copula),基本", 58: "助動詞,だ,連用",
        59: "助動詞,です,基本", 60: "助動詞,です,連用",
        61: "助動詞,ます,基本", 62: "助動詞,ます,連用",
        63: "助動詞,ない,基本", 64: "助動詞,ない,連用",
        65: "助動詞,たい,基本", 66: "助動詞,たい,連用",
        67: "助動詞,ぬ,基本", 68: "助動詞,ぬ,連用",
        69: "助動詞,う/まい,基本", 70: "助動詞,う/まい,連用",
        71: "助動詞,ある,基本", 72: "助動詞,ある,連用",
        73: "助動詞,文語,基本", 74: "助動詞,文語,連用",
        75: "助詞,格助詞,一般", 76: "助詞,格助詞,引用",
        77: "助詞,格助詞,連語", 78: "助詞,格助詞,意志",
        79: "助詞,接続助詞", 80: "助詞,係助詞", 81: "助詞,副助詞",
        82: "助詞,終助詞", 83: "助詞,並立助詞",
        84: "助詞,副助詞/並立/終助詞", 85: "助詞,連体化(の)",
        86: "助詞,副詞化(に)", 87: "助詞,助詞連続", 88: "助詞,特殊",
        90: "副詞,一般", 91: "副詞,助詞類接続",
        95: "連体詞", 98: "接続詞", 100: "感動詞",
        102: "接頭詞,名詞接続", 103: "接頭詞,動詞接続",
        104: "接頭詞,形容詞接続", 105: "接頭詞,数接続",
        106: "記号,句点", 107: "記号,読点", 108: "記号,括弧開",
        109: "記号,括弧閉", 110: "記号,アルファベット", 111: "記号,一般",
        112: "空白", 115: "フィラー", 117: "その他",
    }

    used_groups = sorted(counts.keys())
    print(f"\n  Group statistics ({len(used_groups)} groups used):", file=sys.stderr)
    for gid in used_groups:
        name = GROUP_NAMES.get(gid, "?")
        print(f"    Group {gid:3d}: {counts[gid]:4d} POS IDs  ({name})", file=sys.stderr)


def main():
    output_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        SCRIPT_DIR, "..", "ime-core", "src", "main", "assets", "dict"
    )
    output_dir = os.path.abspath(output_dir)
    os.makedirs(output_dir, exist_ok=True)

    NUM_GROUPS = discover_num_groups()

    print("=== Mozc → Nacre Dictionary Converter V2 (128 groups) ===", file=sys.stderr)

    # Step 1: Parse id.def
    print("\n[1/3] Loading POS definitions...", file=sys.stderr)
    id_def_text = load_or_download("id.def", ID_DEF_URL, "id.def")
    if not id_def_text:
        print("FATAL: Cannot get id.def", file=sys.stderr)
        sys.exit(1)
    id_to_group = parse_id_def(id_def_text)
    print_group_stats(id_to_group)

    # Step 2: Connection matrix
    print("\n[2/3] Loading connection matrix...", file=sys.stderr)
    conn_text = load_or_download("connection_single_column.txt", CONN_URL, "connection_single_column.txt")
    if not conn_text:
        print("FATAL: Cannot get connection matrix", file=sys.stderr)
        sys.exit(1)
    matrix = parse_connection_matrix(conn_text, id_to_group, NUM_GROUPS)

    # Write connection matrix
    conn_path = os.path.join(output_dir, "connection_group.tsv")
    with open(conn_path, "w") as f:
        f.write(f"# Mozc connection cost matrix — {NUM_GROUPS} POS groups (v2)\n")
        f.write(f"# Generated by convert_mozc_dict_v2.py\n")
        f.write(f"# Groups: 0=BOS/EOS, 1-19=名詞, 20-39=動詞, 40-54=形容詞,\n")
        f.write(f"# 55-74=助動詞, 75-89=助詞, 90-94=副詞, 95-97=連体詞,\n")
        f.write(f"# 98-99=接続詞, 100-101=感動詞, 102-105=接頭詞, 106-114=記号,\n")
        f.write(f"# 115-116=フィラー, 117-119=その他, 120-127=reserved\n")
        f.write(f"{NUM_GROUPS}\n")
        for row in matrix:
            f.write("\t".join(str(v) for v in row) + "\n")
    print(f"  Written: {conn_path}", file=sys.stderr)

    # Print some interesting connection costs
    print(f"\n  Sample connection costs (higher = less natural):", file=sys.stderr)
    samples = [
        (1, 75, "名詞→格助詞(が/を)"),
        (1, 80, "名詞→係助詞(は/も)"),
        (1, 82, "名詞→終助詞(よ/ね)"),
        (75, 1, "格助詞→名詞"),
        (75, 75, "格助詞→格助詞"),
        (80, 20, "係助詞→動詞一段基本"),
        (80, 25, "係助詞→動詞五段基本"),
        (25, 79, "動詞五段基本→接続助詞"),
        (25, 106, "動詞五段基本→句点"),
        (21, 55, "動詞一段連用→助動詞た基本"),
        (26, 55, "動詞五段連用→助動詞た基本"),
        (55, 106, "助動詞た→句点"),
        (40, 106, "形容詞基本→句点"),
        (44, 1, "形容詞体言接続→名詞"),
        (85, 1, "助詞の→名詞"),
        (0, 1, "BOS→名詞"),
        (0, 90, "BOS→副詞"),
        (106, 0, "句点→EOS"),
    ]
    for lg, rg, desc in samples:
        if lg < NUM_GROUPS and rg < NUM_GROUPS:
            print(f"    {desc}: {matrix[lg][rg]}", file=sys.stderr)

    # Step 3: Dictionary files
    print("\n[3/3] Loading dictionary files...", file=sys.stderr)
    all_entries = []
    for fname in DICT_FILES:
        url = f"{MOZC_BASE}/{fname}"
        text = load_or_download(fname, url, fname)
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

    # Sort by reading
    sorted_entries = sorted(
        ((r, s, lg, rg, c) for (r, s), (lg, rg, c) in seen.items()),
        key=lambda x: (x[0], x[4])
    )

    # Write plain TSV
    dict_path = os.path.join(output_dir, "mozc_dict.tsv")
    with open(dict_path, "w") as f:
        for reading, surface, lg, rg, cost in sorted_entries:
            f.write(f"{reading}\t{surface}\t{lg}\t{rg}\t{cost}\n")

    print(f"\n  Total entries: {len(sorted_entries)}", file=sys.stderr)
    print(f"  Written: {dict_path}", file=sys.stderr)

    # Gzipped version (mozc_dict.bin is what Kotlin tries first)
    gz_path = os.path.join(output_dir, "mozc_dict.bin")
    with open(dict_path, "rb") as f_in:
        with gzip.open(gz_path, "wb", compresslevel=9) as f_out:
            f_out.write(f_in.read())
    raw_size = os.path.getsize(dict_path)
    gz_size = os.path.getsize(gz_path)
    print(f"  Raw: {raw_size/1024/1024:.1f}MB → Gzip: {gz_size/1024/1024:.1f}MB", file=sys.stderr)

    # Group distribution in dictionary
    group_entry_counts = defaultdict(int)
    for _, _, lg, rg, _ in sorted_entries:
        group_entry_counts[lg] += 1
    top_groups = sorted(group_entry_counts.items(), key=lambda x: -x[1])[:20]
    print(f"\n  Top 20 left-groups by entry count:", file=sys.stderr)
    for gid, cnt in top_groups:
        print(f"    Group {gid:3d}: {cnt:7d} entries", file=sys.stderr)

    print(f"\n=== Done ===", file=sys.stderr)
    print(f"  Dictionary: {len(sorted_entries)} entries", file=sys.stderr)
    print(f"  Connection: {NUM_GROUPS}x{NUM_GROUPS} matrix", file=sys.stderr)
    print(f"  Output: {output_dir}/", file=sys.stderr)


if __name__ == "__main__":
    main()

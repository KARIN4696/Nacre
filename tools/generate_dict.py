#!/usr/bin/env python3
"""
Generate Nacre dictionary TSV from Mozc OSS dictionary data.

Mozc dictionary format (dictionary*.txt in src/data/dictionary_oss/):
  reading\tlid\trid\tcost\tsurface
  e.g.: かんじ\t1847\t1847\t5000\t漢字

Output format (nacre_dict.tsv):
  reading\tsurface\tcost

Usage:
  1. Clone Mozc: git clone --depth 1 https://github.com/google/mozc.git
  2. Run: python3 generate_dict.py mozc/src/data/dictionary_oss/ > nacre_dict.tsv
  3. Copy to: app/src/main/assets/dict/nacre_dict.tsv

Or run with --download to auto-download:
  python3 generate_dict.py --download
"""

import os
import sys
import glob

def parse_mozc_dict(dict_dir: str) -> list[tuple[str, str, int]]:
    """Parse all dictionary*.txt files from Mozc OSS data."""
    entries = []

    for filepath in sorted(glob.glob(os.path.join(dict_dir, "dictionary*.txt"))):
        with open(filepath, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) >= 5:
                    reading = parts[0]
                    cost = int(parts[3]) if parts[3].isdigit() else 10000
                    surface = parts[4]

                    # Skip entries with empty reading/surface
                    if not reading or not surface:
                        continue
                    # Skip very high cost entries (rare/noise)
                    if cost > 15000:
                        continue

                    entries.append((reading, surface, cost))

    return entries


def parse_single_kanji(dict_dir: str) -> list[tuple[str, str, int]]:
    """Parse single_kanji*.txt (single character conversions)."""
    entries = []

    for filepath in sorted(glob.glob(os.path.join(dict_dir, "single_kanji*.txt"))):
        with open(filepath, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) >= 5:
                    reading = parts[0]
                    cost = int(parts[3]) if parts[3].isdigit() else 10000
                    surface = parts[4]
                    if reading and surface:
                        entries.append((reading, surface, cost))

    return entries


def download_mozc_dict() -> str:
    """Download Mozc dictionary files."""
    import subprocess
    import tempfile

    tmpdir = tempfile.mkdtemp(prefix="mozc_dict_")
    dict_dir = os.path.join(tmpdir, "mozc", "src", "data", "dictionary_oss")

    print("Downloading Mozc dictionary...", file=sys.stderr)
    subprocess.run(
        [
            "git", "clone", "--depth", "1", "--filter=blob:none", "--sparse",
            "https://github.com/google/mozc.git",
            os.path.join(tmpdir, "mozc"),
        ],
        check=True,
        capture_output=True,
    )
    subprocess.run(
        ["git", "sparse-checkout", "set", "src/data/dictionary_oss"],
        cwd=os.path.join(tmpdir, "mozc"),
        check=True,
        capture_output=True,
    )
    print(f"Downloaded to {dict_dir}", file=sys.stderr)
    return dict_dir


def main():
    if "--download" in sys.argv:
        dict_dir = download_mozc_dict()
    elif len(sys.argv) >= 2:
        dict_dir = sys.argv[1]
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(1)

    entries = parse_mozc_dict(dict_dir)
    entries += parse_single_kanji(dict_dir)

    # Deduplicate: keep lowest cost for each (reading, surface) pair
    seen = {}
    for reading, surface, cost in entries:
        key = (reading, surface)
        if key not in seen or cost < seen[key]:
            seen[key] = cost

    # Sort by reading, then cost
    sorted_entries = sorted(seen.items(), key=lambda x: (x[0][0], x[1]))

    # Output TSV
    print("# Nacre Dictionary - Generated from Mozc OSS (NAIST dictionary)")
    print(f"# Entries: {len(sorted_entries)}")
    print("# Format: reading\\tsurface\\tcost")

    for (reading, surface), cost in sorted_entries:
        print(f"{reading}\t{surface}\t{cost}")

    print(f"Generated {len(sorted_entries)} entries", file=sys.stderr)


if __name__ == "__main__":
    main()

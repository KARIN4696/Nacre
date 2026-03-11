#!/usr/bin/env python3
"""
Build English dictionary for Nacre IME.
Generates english_full.tsv with ~50k English words, costs based on word frequency.

Sources: Built-in word list from NLTK/system dictionaries.
Output: TSV format: lowercase_key\tSurface\tcost

Usage: python3 build_english_dict.py > ime-core/src/main/assets/dict/english_full.tsv
"""

import sys
import os

# Top ~50k English words with approximate frequency ranks.
# We use a pragmatic approach: download from a public frequency list or generate from corpus.
# For offline build, we'll use a curated list approach.

def get_word_list():
    """Try multiple sources for English word frequency list."""
    words = {}

    # Source 1: Try Google 20k/10k most common words (bundled)
    for fname in ['google-20000-english.txt', 'google-10000-english.txt']:
        fpath = os.path.join(os.path.dirname(__file__), fname)
        if os.path.exists(fpath):
            with open(fpath, 'r') as f:
                for rank, line in enumerate(f, 1):
                    word = line.strip().lower()
                    if word and len(word) >= 2 and word.isalpha():
                        if word not in words:
                            words[word] = rank
            print(f"Loaded {len(words)} words from {fname}", file=sys.stderr)
            break  # Use largest available list

    # Source 2: /usr/share/dict/words (Unix systems)
    dict_paths = ['/usr/share/dict/words', '/usr/share/dict/american-english']
    for dpath in dict_paths:
        if os.path.exists(dpath):
            with open(dpath, 'r') as f:
                for line in f:
                    word = line.strip()
                    if word and len(word) >= 2 and word.isalpha() and "'" not in word:
                        lower = word.lower()
                        if lower not in words:
                            words[lower] = 50000 + len(words)
            print(f"Loaded total {len(words)} words (added from {dpath})", file=sys.stderr)
            break

    # Source 3: Hardcoded essential words (always included)
    essentials = {
        # Programming
        'the': 1, 'of': 2, 'and': 3, 'to': 4, 'in': 5, 'is': 6, 'it': 7,
        'for': 8, 'that': 9, 'was': 10, 'on': 11, 'are': 12, 'with': 13,
        'this': 14, 'be': 15, 'not': 16, 'have': 17, 'from': 18, 'or': 19,
        'by': 20, 'but': 21, 'at': 22, 'an': 23, 'they': 24, 'which': 25,
        'you': 26, 'we': 27, 'can': 28, 'had': 29, 'her': 30,
        'all': 31, 'there': 32, 'been': 33, 'has': 34, 'when': 35,
        'who': 36, 'will': 37, 'more': 38, 'if': 39, 'no': 40,
        'out': 41, 'do': 42, 'so': 43, 'what': 44, 'up': 45,
        'their': 46, 'about': 47, 'would': 48, 'just': 49, 'him': 50,
        # Tech/programming
        'function': 100, 'class': 101, 'return': 102, 'import': 103,
        'string': 104, 'int': 105, 'float': 106, 'boolean': 107,
        'null': 108, 'void': 109, 'public': 110, 'private': 111,
        'static': 112, 'final': 113, 'const': 114, 'var': 115,
        'let': 116, 'val': 117, 'fun': 118, 'interface': 119,
        'abstract': 120, 'override': 121, 'suspend': 122, 'async': 123,
        'await': 124, 'yield': 125, 'throw': 126, 'catch': 127,
        'try': 128, 'finally': 129, 'if': 130, 'else': 131,
        'while': 132, 'for': 133, 'switch': 134, 'case': 135,
        'break': 136, 'continue': 137, 'default': 138, 'new': 139,
        'delete': 140, 'typeof': 141, 'instanceof': 142,
        # Common tech terms
        'google': 200, 'android': 201, 'kotlin': 202, 'java': 203,
        'python': 204, 'javascript': 205, 'typescript': 206,
        'react': 207, 'flutter': 208, 'swift': 209, 'rust': 210,
        'docker': 211, 'kubernetes': 212, 'github': 213, 'linux': 214,
        'ubuntu': 215, 'windows': 216, 'macos': 217, 'ios': 218,
        'api': 219, 'http': 220, 'https': 221, 'json': 222,
        'html': 223, 'css': 224, 'sql': 225, 'database': 226,
        'server': 227, 'client': 228, 'request': 229, 'response': 230,
        'error': 231, 'debug': 232, 'test': 233, 'build': 234,
        'deploy': 235, 'config': 236, 'setup': 237, 'install': 238,
        # Brand names (proper case)
        'Amazon': 250, 'Apple': 251, 'Microsoft': 252, 'Facebook': 253,
        'Twitter': 254, 'Instagram': 255, 'YouTube': 256, 'Netflix': 257,
        'Spotify': 258, 'Tesla': 259, 'Samsung': 260, 'Sony': 261,
        'Nintendo': 262, 'PlayStation': 263, 'Xbox': 264,
        'ChatGPT': 265, 'Claude': 266, 'Gemini': 267,
        # Common English words for daily use
        'hello': 300, 'thanks': 301, 'please': 302, 'sorry': 303,
        'good': 304, 'great': 305, 'nice': 306, 'cool': 307,
        'awesome': 308, 'beautiful': 309, 'important': 310,
        'interesting': 311, 'different': 312, 'possible': 313,
        'available': 314, 'necessary': 315, 'information': 316,
        'development': 317, 'experience': 318, 'environment': 319,
        'application': 320, 'management': 321, 'performance': 322,
        'technology': 323, 'communication': 324, 'organization': 325,
        'international': 326, 'professional': 327, 'government': 328,
        'understand': 329, 'remember': 330, 'consider': 331,
        'continue': 332, 'believe': 333, 'already': 334,
        'together': 335, 'something': 336, 'sometimes': 337,
        'everything': 338, 'everyone': 339, 'anything': 340,
        'between': 341, 'through': 342, 'because': 343,
        'another': 344, 'without': 345, 'however': 346,
        'although': 347, 'before': 348, 'after': 349,
        'during': 350, 'still': 351, 'again': 352,
        'always': 353, 'never': 354, 'often': 355,
        'really': 356, 'actually': 357, 'probably': 358,
        'maybe': 359, 'enough': 360, 'example': 361,
        'problem': 362, 'question': 363, 'answer': 364,
        'message': 365, 'today': 366, 'tomorrow': 367,
        'yesterday': 368, 'morning': 369, 'evening': 370,
        'night': 371, 'week': 372, 'month': 373, 'year': 374,
        'time': 375, 'people': 376, 'world': 377, 'life': 378,
        'work': 379, 'school': 380, 'house': 381, 'money': 382,
        'water': 383, 'food': 384, 'music': 385, 'movie': 386,
        'book': 387, 'phone': 388, 'email': 389, 'password': 390,
    }
    for word, rank in essentials.items():
        lower = word.lower()
        if lower not in words or words[lower] > rank:
            words[lower] = rank

    return words


def rank_to_cost(rank):
    """Convert frequency rank to IME cost. Lower rank = lower cost = higher priority."""
    if rank <= 100:
        return 2000
    elif rank <= 500:
        return 3000
    elif rank <= 1000:
        return 3500
    elif rank <= 5000:
        return 4500
    elif rank <= 10000:
        return 5500
    elif rank <= 20000:
        return 6500
    elif rank <= 50000:
        return 7500
    else:
        return 8500


def proper_case(word, rank):
    """Generate proper-cased variant if applicable."""
    # Known proper case words
    proper = {
        'amazon': 'Amazon', 'apple': 'Apple', 'microsoft': 'Microsoft',
        'facebook': 'Facebook', 'twitter': 'Twitter', 'instagram': 'Instagram',
        'youtube': 'YouTube', 'netflix': 'Netflix', 'spotify': 'Spotify',
        'tesla': 'Tesla', 'samsung': 'Samsung', 'sony': 'Sony',
        'nintendo': 'Nintendo', 'playstation': 'PlayStation', 'xbox': 'Xbox',
        'google': 'Google', 'android': 'Android', 'kotlin': 'Kotlin',
        'java': 'Java', 'python': 'Python', 'javascript': 'JavaScript',
        'typescript': 'TypeScript', 'react': 'React', 'flutter': 'Flutter',
        'swift': 'Swift', 'rust': 'Rust', 'docker': 'Docker',
        'kubernetes': 'Kubernetes', 'github': 'GitHub', 'linux': 'Linux',
        'ubuntu': 'Ubuntu', 'windows': 'Windows', 'macos': 'macOS',
        'ios': 'iOS', 'chatgpt': 'ChatGPT', 'claude': 'Claude',
        'gemini': 'Gemini', 'openai': 'OpenAI', 'anthropic': 'Anthropic',
        'monday': 'Monday', 'tuesday': 'Tuesday', 'wednesday': 'Wednesday',
        'thursday': 'Thursday', 'friday': 'Friday', 'saturday': 'Saturday',
        'sunday': 'Sunday', 'january': 'January', 'february': 'February',
        'march': 'March', 'april': 'April', 'may': 'May', 'june': 'June',
        'july': 'July', 'august': 'August', 'september': 'September',
        'october': 'October', 'november': 'November', 'december': 'December',
    }
    return proper.get(word, word)


def main():
    words = get_word_list()
    print(f"Total words: {len(words)}", file=sys.stderr)

    # Output TSV: key(lowercase) \t Surface \t cost
    # Multiple variants per word: lowercase, Capitalized, ProperCase
    seen = set()
    entries = []

    for word, rank in sorted(words.items(), key=lambda x: x[1]):
        if len(word) < 2:
            continue
        cost = rank_to_cost(rank)
        key = word.lower()

        # Lowercase variant
        if key not in seen:
            entries.append((key, word, cost))
            seen.add(key)

        # Capitalized variant (slightly higher cost)
        cap = word.capitalize()
        cap_key = f"{key}_cap"
        if cap != word and cap_key not in seen:
            entries.append((key, cap, cost + 200))
            seen.add(cap_key)

        # Proper case variant (brand names etc.)
        proper = proper_case(word, rank)
        proper_key = f"{key}_proper"
        if proper != word and proper != cap and proper_key not in seen:
            entries.append((key, proper, cost - 100))  # Prefer proper case
            seen.add(proper_key)

    # Sort by key for efficient loading
    entries.sort(key=lambda x: (x[0], x[2]))

    for key, surface, cost in entries:
        print(f"{key}\t{surface}\t{cost}")

    print(f"Output: {len(entries)} entries", file=sys.stderr)


if __name__ == '__main__':
    main()

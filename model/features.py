"""Text normalization and feature hashing.

MUST stay identical to app/src/main/java/com/ozer/assistant/nlu/Features.kt —
the app computes the same features on the phone and feeds them to the weights
trained here.
"""

DIM = 1 << 14

FINALS = {"ך": "כ", "ם": "מ", "ן": "נ", "ף": "פ", "ץ": "צ"}
DROP = set("'\"`’‘“”׳״")


def normalize(text: str) -> str:
    out = []
    for ch in text.lower():
        c = ord(ch)
        if ch in DROP:
            continue
        if 0x0591 <= c <= 0x05C7 and c != 0x05BE:  # niqqud / cantillation
            continue
        ch = FINALS.get(ch, ch)
        c = ord(ch)
        if 0x05D0 <= c <= 0x05EA or "a" <= ch <= "z" or "0" <= ch <= "9":
            out.append(ch)
        else:
            out.append(" ")
    return " ".join("".join(out).split())


def _digits_to_hash(tok: str) -> str:
    out = []
    prev_digit = False
    for ch in tok:
        if "0" <= ch <= "9":
            if not prev_digit:
                out.append("#")
            prev_digit = True
        else:
            out.append(ch)
            prev_digit = False
    return "".join(out)


def tokens(text: str):
    n = normalize(text)
    return [_digits_to_hash(t) for t in n.split(" ")] if n else []


def fnv1a(s: str) -> int:
    h = 0x811C9DC5
    for ch in s:
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def feature_strings(text: str):
    toks = tokens(text)
    feats = []
    prev = "^"
    for t in toks:
        feats.append("w|" + t)
        feats.append("b|" + prev + "|" + t)
        prev = t
        padded = "<" + t + ">"
        for n in (2, 3, 4):
            for i in range(len(padded) - n + 1):
                feats.append("c|" + padded[i:i + n])
    return feats


def featurize(text: str):
    """Returns sorted list of (index, value), L2-normalized."""
    counts = {}
    for f in feature_strings(text):
        idx = fnv1a(f) % DIM
        counts[idx] = counts.get(idx, 0.0) + 1.0
    norm = sum(v * v for v in counts.values()) ** 0.5
    if norm == 0:
        return []
    return sorted((k, v / norm) for k, v in counts.items())

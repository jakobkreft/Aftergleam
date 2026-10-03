"""Is a focus tag a literal word to find, or a topic to rank by? What each reading does.

For a diverse set of things scientists actually type (method names, datasets, genes,
organisms, missions, laws, broad topics), fetch real recent papers that the servers return
for them, then compare two matching rules on each paper's title and abstract:

  current   the app's focus rule: the ranker's terms, words of one or two letters dropped,
            split at hyphens, matched as a phrase of terms
  literal   the text as typed, found as written: case ignored, hyphen, space or nothing
            between its parts, an optional plural, and nothing alphanumeric either side

    .venv/bin/python keyword_audit.py
"""
from __future__ import annotations
import json, re, time, urllib.parse, urllib.request
from pathlib import Path

ROOT = Path(__file__).parent
KOTLIN = ROOT.parent / "android/app/src/main/java/si/jakobkreft/aftergleam/rank/Tfidf.kt"
UA = "Aftergleam-research (mailto:user@aftergleam.app)"
PREFIXES = "prefix:10.64898,prefix:10.26434,prefix:10.31234,prefix:10.31235,prefix:10.35542,prefix:10.31228"

KEYWORDS = [
    "H-Net", "LoRA", "U-Net", "NeRF", "RL", "graph neural network",
    "Sentinel-2", "earth observation", "CRISPR", "C. elegans", "BRCA1", "gut microbiome",
    "long COVID", "SARS-CoV-2", "JWST", "dark matter", "GDPR", "perovskite",
    "working memory", "transformer",
]

STOP = set(re.findall(r'"([a-z]+)"', KOTLIN.read_text().split("private val STOP = setOf(")[1].split(")")[0]))


def terms(doc: str) -> list[str]:
    d = re.sub(r"\$[^$]*\$", " ", re.sub(r"https?://\S+", " ", doc.lower()))
    words = [w for w in re.split(r"[^a-z0-9]+", d) if len(w) > 2 and w not in STOP and not w.isdigit()]
    return words + [a + "_" + b for a, b in zip(words, words[1:])] if len(words) > 1 else words


def forms(w):
    return {f for f in (w, w + "s", w + "es", w.removesuffix("s"), w.removesuffix("es")) if len(f) > 2}


def current(tag: str, text: str) -> bool:
    words = [t for t in terms(tag) if "_" not in t]
    toks = set(terms(text))
    if not words: return False
    if len(words) == 1: return bool(forms(words[0]) & toks)
    return all(any(f"{x}_{y}" in toks for x in forms(a) for y in forms(b)) for a, b in zip(words, words[1:]))


def literal_re(tag: str) -> re.Pattern | None:
    parts = re.findall(r"[A-Za-z0-9]+", tag)
    if not parts: return None
    body = r"[\s\-‐‑–./]{0,2}".join(re.escape(p) for p in parts)
    return re.compile(r"(?<![A-Za-z0-9])" + body + r"(?:s|es)?(?![A-Za-z0-9])", re.I)


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode()


def fetch(tag: str) -> list[tuple[str, str, str]]:
    out = []
    q = urllib.parse.quote(f'all:"{tag}"')
    xml = get(f"https://export.arxiv.org/api/query?search_query={q}&sortBy=submittedDate&sortOrder=descending&max_results=40")
    for e in xml.split("<entry>")[1:]:
        t = re.search(r"<title>(.*?)</title>", e, re.S).group(1)
        a = re.search(r"<summary>(.*?)</summary>", e, re.S).group(1)
        out.append(("arXiv", " ".join(t.split()), " ".join(a.split())))
    cr = json.loads(get("https://api.crossref.org/works?query.bibliographic=" + urllib.parse.quote(tag) +
                        f"&filter={PREFIXES},type:posted-content,from-posted-date:2026-07-01&rows=40"
                        "&select=title,abstract,resource"))
    for it in cr["message"]["items"]:
        t = re.sub(r"<[^>]+>", " ", (it.get("title") or [""])[0])
        a = re.sub(r"<[^>]+>", " ", it.get("abstract") or "")
        host = (it.get("resource") or {}).get("primary", {}).get("URL", "").split("/")[2:3]
        out.append((host[0] if host else "crossref", " ".join(t.split()), " ".join(a.split())))
    return out


def main():
    rows = []
    for tag in KEYWORDS:
        papers = fetch(tag)
        time.sleep(3.5)  # arXiv asks for one request every three seconds
        lit = literal_re(tag)
        cur = [p for p in papers if current(tag, p[1] + ". " + p[2])]
        exact = [p for p in papers if lit and lit.search(p[1] + ". " + p[2])]
        wrong = [p for p in cur if p not in exact]
        missed = [p for p in exact if p not in cur]
        words = [t for t in terms(tag) if "_" not in t]
        rows.append((tag, words, len(papers), len(cur), len(exact), len(wrong), len(missed),
                     wrong[:1], missed[:1]))
        print(f"{tag:20} app matches on {str(words):32} fetched {len(papers):3}  "
              f"current {len(cur):3}  literal {len(exact):3}  current-wrong {len(wrong):3}  current-missed {len(missed):3}")
        for src, t, a in wrong[:1]:
            hit = re.search(r".{0,40}\b(" + "|".join(map(re.escape, words or ['zzz'])) + r")\w*\b.{0,30}", t + ". " + a, re.I)
            print(f"    wrong:  [{src}] {t[:70]}  …{hit.group(0) if hit else ''}…")
        for src, t, a in missed[:1]:
            hit = lit.search(t + ". " + a)
            s = (t + ". " + a)
            print(f"    missed: [{src}] {t[:70]}  …{s[max(0, hit.start() - 30):hit.end() + 20]}…")


if __name__ == "__main__":
    main()

"""Parse a BibTeX/RIS library into E1 positives, and resolve entries to arXiv abstracts.

This is deliberately the same code path D10 calls the highest-leverage onboarding route,
so whatever match rate we measure here is the match rate real users will get on import.
Treat a poor number as a finding about the product, not just about the experiment.

Resolution order, cheapest first:
  1. explicit arXiv id in the entry (eprint field, or an arxiv.org URL anywhere)
  2. DOI of the form 10.48550/arXiv.<id>
  3. exact-ish title match against the harvested corpus
  4. arXiv API title search (rate limited, 3s apart)

Entries that resolve to nothing are reported, not silently dropped: the miss rate is
itself an E1 result.
"""

from __future__ import annotations

import re
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass

from lxml import etree

UA = "Aftergleam/0.1 (offline arXiv reader; library import)"
API = "https://export.arxiv.org/api/query"
ATOM = {"a": "http://www.w3.org/2005/Atom"}

# 1706.03762, 1706.03762v5, math/0211159 (pre-2007 style)
ARXIV_ID = re.compile(
    r"(?:arxiv[.:/ ]+)?(\d{4}\.\d{4,5}|[a-z-]+(?:\.[A-Z]{2})?/\d{7})(?:v\d+)?",
    re.I,
)
DOI_ARXIV = re.compile(r"10\.48550/arxiv\.(\S+)", re.I)


@dataclass(slots=True)
class Entry:
    key: str
    title: str
    year: str
    arxiv_id: str
    doi: str
    raw: str


def _strip_braces(s: str) -> str:
    s = re.sub(r"[{}]", "", s or "")
    return " ".join(s.split())


def parse_bibtex(text: str) -> list[Entry]:
    """Brace-counting splitter. Handles nested braces, which regex-per-field does not."""
    entries: list[Entry] = []
    for m in re.finditer(r"@(\w+)\s*\{", text):
        if m.group(1).lower() in ("comment", "preamble", "string"):
            continue
        i = m.end()
        depth, start = 1, i
        while i < len(text) and depth:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
            i += 1
        body = text[start : i - 1]
        key = body.split(",", 1)[0].strip()

        fields: dict[str, str] = {}
        for fm in re.finditer(r"(\w+)\s*=\s*", body):
            j = fm.end()
            if j >= len(body):
                break
            if body[j] == "{":
                d, s2 = 1, j + 1
                j += 1
                while j < len(body) and d:
                    if body[j] == "{":
                        d += 1
                    elif body[j] == "}":
                        d -= 1
                    j += 1
                val = body[s2 : j - 1]
            elif body[j] == '"':
                s2 = j + 1
                j = body.find('"', s2)
                val = body[s2 : j if j > 0 else len(body)]
            else:
                k = re.match(r"[^,\n]*", body[j:])
                val = k.group(0) if k else ""
            fields[fm.group(1).lower()] = _strip_braces(val)

        blob = " ".join(fields.values())
        aid = ""
        if fields.get("eprint") and "arxiv" in (fields.get("archiveprefix", "") + fields.get("eprinttype", "")).lower():
            aid = fields["eprint"]
        if not aid:
            dm = DOI_ARXIV.search(blob)
            if dm:
                aid = dm.group(1)
        if not aid:
            for field in ("url", "note", "howpublished", "eprint"):
                am = ARXIV_ID.search(fields.get(field, ""))
                if am:
                    aid = am.group(1)
                    break
        entries.append(
            Entry(
                key=key,
                title=fields.get("title", ""),
                year=fields.get("year", ""),
                arxiv_id=re.sub(r"v\d+$", "", aid or ""),
                doi=fields.get("doi", ""),
                raw=blob[:400],
            )
        )
    return entries


def parse_ris(text: str) -> list[Entry]:
    entries, cur = [], {}
    for line in text.splitlines():
        m = re.match(r"^([A-Z][A-Z0-9])\s+-\s*(.*)$", line)
        if not m:
            continue
        tag, val = m.group(1), m.group(2).strip()
        if tag == "TY":
            cur = {}
        elif tag == "ER":
            blob = " ".join(cur.values())
            am = ARXIV_ID.search(blob) if "arxiv" in blob.lower() else None
            entries.append(
                Entry(
                    key=cur.get("ID", ""),
                    title=cur.get("TI") or cur.get("T1", ""),
                    year=(cur.get("PY") or cur.get("Y1", ""))[:4],
                    arxiv_id=am.group(1) if am else "",
                    doi=cur.get("DO", ""),
                    raw=blob[:400],
                )
            )
        else:
            cur[tag] = (cur.get(tag, "") + " " + val).strip()
    return entries


def load(path: str) -> list[Entry]:
    text = open(path, encoding="utf-8", errors="replace").read()
    return parse_ris(text) if text.lstrip().startswith("TY  -") else parse_bibtex(text)


def norm_title(t: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (t or "").lower()).strip()


def fetch_by_ids(ids: list[str], batch: int = 100) -> dict[str, dict]:
    """arXiv API id_list lookup. One request per `batch` ids, 3s apart."""
    out: dict[str, dict] = {}
    for i in range(0, len(ids), batch):
        chunk = [c for c in ids[i : i + batch] if c]
        if not chunk:
            continue
        url = f"{API}?{urllib.parse.urlencode({'id_list': ','.join(chunk), 'max_results': len(chunk)})}"
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=60) as r:
            root = etree.fromstring(r.read())
        for e in root.iterfind("a:entry", ATOM):
            # Pre-2007 ids contain a slash (math/0211159), so strip the URL prefix
            # rather than splitting on the last '/' — that would drop the archive.
            raw = re.sub(r"^https?://arxiv\.org/abs/", "", e.findtext("a:id", "", ATOM) or "")
            aid = re.sub(r"v\d+$", "", raw)
            out[aid] = {
                "arxiv_id": aid,
                "title": " ".join((e.findtext("a:title", "", ATOM) or "").split()),
                "abstract": " ".join((e.findtext("a:summary", "", ATOM) or "").split()),
                "categories": " ".join(
                    c.get("term", "") for c in e.iterfind("a:category", ATOM)
                ),
                "published": e.findtext("a:published", "", ATOM)[:10],
            }
        if i + batch < len(ids):
            time.sleep(3.0)
    return out


# --- title resolution -------------------------------------------------------
# A hand-written .bib often has no eprint or DOI field at all (measured: 0/104 in
# the first real library tested). Title search is then the ONLY path, so its match
# rate is effectively the product's BibTeX-import match rate. Worth measuring honestly.

_LATEX = re.compile(r"\\[a-zA-Z]+\s*|[${}\\]")


def clean_title(t: str) -> str:
    """Strip LaTeX commands and math so 'Any-size-diffusion: $\\infty$-Diff' searches sanely."""
    return " ".join(_LATEX.sub(" ", t or "").split())


def _tokens(t: str) -> set:
    return set(norm_title(t).split())


def title_similarity(a: str, b: str) -> float:
    ta, tb = _tokens(a), _tokens(b)
    if not ta or not tb:
        return 0.0
    return len(ta & tb) / len(ta | tb)


def resolve_by_title(title: str, threshold: float = 0.6, max_results: int = 5):
    """Search arXiv by title; return the best match above `threshold` Jaccard, else None.

    The threshold guards against arXiv's search happily returning something adjacent:
    a query for 'Denoising diffusion probabilistic models' will also match a dozen
    papers that merely cite it.
    """
    q = clean_title(title)
    if len(q) < 12:
        return None
    url = f"{API}?" + urllib.parse.urlencode(
        {"search_query": f'ti:"{q}"', "max_results": max_results}
    )
    # Retry properly. Conflating "request failed" with "no such paper" silently turns a
    # flaky network into a fabricated product finding: the first batch run of this
    # function reported 22 papers as unmatched that in fact resolve at similarity 1.00.
    root = None
    for attempt in range(4):
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                root = etree.fromstring(r.read())
            break
        except Exception:
            if attempt == 3:
                raise LookupError(f"arXiv title search failed after 4 attempts: {q[:60]}")
            time.sleep(3.0 * (2**attempt))

    best, best_sim = None, 0.0
    for e in root.iterfind("a:entry", ATOM):
        cand = " ".join((e.findtext("a:title", "", ATOM) or "").split())
        sim = title_similarity(q, cand)
        if sim > best_sim:
            raw = re.sub(r"^https?://arxiv\.org/abs/", "", e.findtext("a:id", "", ATOM) or "")
            best_sim, best = sim, {
                "arxiv_id": re.sub(r"v\d+$", "", raw),
                "title": cand,
                "abstract": " ".join((e.findtext("a:summary", "", ATOM) or "").split()),
                "categories": " ".join(c.get("term", "") for c in e.iterfind("a:category", ATOM)),
                "published": e.findtext("a:published", "", ATOM)[:10],
                "match_sim": sim,
            }
    return best if best_sim >= threshold else None

"""Bulk-harvest arXiv metadata over OAI-PMH.

Uses the `arXivRaw` metadata prefix. It carries the abstract, categories, journal-ref,
the free-text `comments` field where venue acceptance appears ("ICLR 2025 Oral", see
docs/01-findings.md F3), and a full per-version date history.

Three gotchas, all verified live on 2026-09-08:

  * `from`/`until` filter on the OAI datestamp (metadata modification), NOT submission
    date. `from=2026-09-01` returns 2016 papers whose metadata was touched last week.
    That is a feature for the Resurfacer — it is exactly how you detect a paper whose
    comments field just gained "NeurIPS 2025" — but you must filter on `submitted`
    when you want a corpus by submission date.

  * The simpler `arXiv` prefix reports `<created>` as the date of the LATEST version,
    not the original submission. arXiv 1601.04794 (January 2016) reports
    `created=2026-08-30`. Anything age-normalised built on that field is silently
    wrong. `arXivRaw`'s `<version v1><date>` is the real submission date.

  * arXiv asks for one request every three seconds, single connection. Resumption
    tokens make a full harvest slow but cheap; never parallelise it.

Version count comes free with this prefix, which is the "version churn" signal (M5).
"""

from __future__ import annotations

import re
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, asdict
from typing import Iterator

from lxml import etree

OAI = "https://oaipmh.arxiv.org/oai"
NS = {
    "oai": "http://www.openarchives.org/OAI/2.0/",
    "arx": "http://arxiv.org/OAI/arXivRaw/",
}
UA = "Aftergleam/0.1 (offline arXiv reader; OAI-PMH harvester)"
SLEEP = 3.0  # arXiv: one request per three seconds, single connection


@dataclass(slots=True)
class Record:
    arxiv_id: str
    title: str
    abstract: str
    authors: str
    categories: str
    submitted: str      # true v1 date, ISO
    latest: str         # newest version date, ISO
    n_versions: int     # version churn signal (M5)
    comments: str
    journal_ref: str
    doi: str
    datestamp: str


def _text(node, path: str) -> str:
    el = node.find(path, NS)
    return " ".join(el.text.split()) if el is not None and el.text else ""


_MONTHS = {m: i for i, m in enumerate(
    "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split(), start=1)}


def _rfc822_date(s: str) -> str:
    """'Tue, 19 Jan 2016 04:10:52 GMT' -> '2016-01-19'. '' if unparseable."""
    m = re.search(r"(\d{1,2})\s+([A-Z][a-z]{2})\s+(\d{4})", s or "")
    if not m:
        return ""
    day, mon, year = m.groups()
    return f"{year}-{_MONTHS.get(mon, 0):02d}-{int(day):02d}"


def _fetch(params: dict[str, str], retries: int = 5) -> bytes:
    url = f"{OAI}?{urllib.parse.urlencode(params)}"
    delay = SLEEP
    for attempt in range(retries):
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            # 503 with Retry-After is the documented OAI flow-control signal.
            if e.code in (503, 429):
                wait = int(e.headers.get("Retry-After", delay))
                time.sleep(min(wait, 120))
                delay = min(delay * 2, 120)
                continue
            raise
        except Exception:
            if attempt == retries - 1:
                raise
            time.sleep(delay)
            delay = min(delay * 2, 120)
    raise RuntimeError(f"OAI-PMH failed after {retries} attempts: {url}")


def _parse(blob: bytes) -> tuple[list[Record], str | None]:
    root = etree.fromstring(blob)
    err = root.find("oai:error", NS)
    if err is not None:
        code = err.get("code", "")
        # noRecordsMatch is an empty result, not a failure.
        if code == "noRecordsMatch":
            return [], None
        raise RuntimeError(f"OAI-PMH error {code}: {err.text}")

    out: list[Record] = []
    for rec in root.iterfind(".//oai:record", NS):
        header = rec.find("oai:header", NS)
        if header is not None and header.get("status") == "deleted":
            continue
        meta = rec.find(".//arx:arXivRaw", NS)
        if meta is None:
            continue
        vdates = [_rfc822_date(_text(v, "arx:date"))
                  for v in meta.iterfind("arx:version", NS)]
        vdates = [d for d in vdates if d]
        out.append(
            Record(
                arxiv_id=_text(meta, "arx:id"),
                title=_text(meta, "arx:title"),
                abstract=_text(meta, "arx:abstract"),
                authors=_text(meta, "arx:authors"),
                categories=_text(meta, "arx:categories"),
                submitted=vdates[0] if vdates else "",
                latest=vdates[-1] if vdates else "",
                n_versions=len(vdates),
                comments=_text(meta, "arx:comments"),
                journal_ref=_text(meta, "arx:journal-ref"),
                doi=_text(meta, "arx:doi"),
                datestamp=_text(header, "oai:datestamp") if header is not None else "",
            )
        )

    token_el = root.find(".//oai:resumptionToken", NS)
    token = token_el.text.strip() if token_el is not None and token_el.text else None
    return out, token


def harvest(
    oai_set: str = "cs",
    frm: str | None = None,
    until: str | None = None,
    max_records: int | None = None,
    verbose: bool = True,
) -> Iterator[Record]:
    """Yield Records. `frm`/`until` are YYYY-MM-DD on the OAI datestamp."""
    params = {"verb": "ListRecords", "metadataPrefix": "arXivRaw", "set": oai_set}
    if frm:
        params["from"] = frm
    if until:
        params["until"] = until

    seen = 0
    page = 0
    while True:
        recs, token = _parse(_fetch(params))
        page += 1
        for r in recs:
            yield r
            seen += 1
            if max_records and seen >= max_records:
                if verbose:
                    print(f"  harvest: stopped at {seen} records ({page} pages)")
                return
        if verbose:
            print(f"  harvest: page {page}, {seen} records so far", flush=True)
        if not token:
            if verbose:
                print(f"  harvest: complete, {seen} records ({page} pages)")
            return
        # Once a resumption token is in play it is the ONLY permitted argument.
        params = {"verb": "ListRecords", "resumptionToken": token}
        time.sleep(SLEEP)


def to_frame(records):
    import pandas as pd

    return pd.DataFrame([asdict(r) for r in records])

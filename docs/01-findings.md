# Findings — verified against live APIs, 2026-09-08

Every number here came from a live call made today. Raw probe scripts and JSON are in the
session scratchpad; the reproducible versions live in `prototype/aftergleam/`.

---

## F1 — OpenAlex cannot supply the citation layer for arXiv ML preprints

This is the finding that changes the design. The original plan put OpenAlex under the
Resurfacer (M1), citation velocity (E3), and onboarding screen 2. It cannot carry any of them.

**Test.** 120 real cs.LG papers submitted 1–15 March 2025, fetched from the arXiv API exactly as
the app would have seen them on the day. Eighteen months later, each looked up in OpenAlex by
its arXiv DOI (`10.48550/arxiv.<id>`) — the exact call the Resurfacer would make.

| Measure | Result |
|---|---|
| DOI resolution | 110/120 = **92%** (10 × HTTP 404) |
| Records typed `preprint` | 106/110 |
| **Zero citations** | **91%** |
| Median / p90 / max citations | **0 / 0 / 3** |
| Papers with ≥10 citations | **0** |

A max of 3 citations across 120 cs.LG papers from 18 months ago is not plausible. It is a data
artefact, and the cause is structural:

- **Proteina** (arXiv 2503.00710, ICLR 2025 **Oral**): OpenAlex `cited_by_count` = **3**.
  Semantic Scholar for the same arXiv ID = **99** (20 influential). A 33× gap.
  OpenAlex holds exactly one record for it — the preprint — so this is not a preprint/published
  split that merging could repair.
- That record (`W4415082968`) has **`referenced_works` = 0** and **`cited_by_api_url` absent**.
  OpenAlex arXiv preprint records are metadata stubs carrying no edges in either direction.
- The citing side is missing too. Venue coverage in OpenAlex:
  **NeurIPS = 169 works. ICML = 1,871. ICLR = 895** — across *all years*. NeurIPS alone publishes
  ~4,500 papers a year. OpenAlex's citation graph is built largely from Crossref reference lists,
  and the ML conference ecosystem (OpenReview/PMLR) is barely in Crossref.

The perverse consequence: **OpenAlex is weakest in precisely the fields this app targets.**

**A second, subtler trap.** My first citation sample looked healthy — median 14, 0% zeros. It was
biased: filtering on `locations.source.id:<arXiv>` returns *merged* records (`type: article`)
for papers that reached a journal, i.e. a survivor sample. The honest query is by arXiv DOI, and
it gives the table above. Worth remembering when reading anyone else's OpenAlex citation stats.

**Related caution.** *Attention Is All You Need* in OpenAlex: `cited_by_count` **7,290**,
`publication_year` **2025**, 11 locations, 8 of them ghost records under an unfamiliar
`10.65215/*` prefix with null sources, and incoherent `counts_by_year`
(2021: 2,988 → 2023: 19 → 2026: 714). Its arXiv DOI 404s outright. Famous papers — the ones
onboarding screen 2 wants — are where OpenAlex is *least* reliable.

## F2 — Semantic Scholar has the right data and the wrong access model

S2 is arXiv-native and covers ML properly (Proteina 99, DeepSeek-R1 5,609 by arXiv ID).

But unauthenticated access is a **shared pool across all anonymous clients**. In practice I was
429'd on the 2nd call ~5 s apart, and 3 of 5 lookups failed outright after backoff. An API key
gives ~1 req/s, and a FOSS app cannot ship a key — it is in the source.

So S2 is unusable as a *live per-device* API for a shipped app. Two things follow, and the
second is the more important one:

1. The S2 Academic Graph bulk datasets (ODC-BY 1.0, 200M+ papers, 2.4B+ citation edges,
   free for non-commercial use) can be preprocessed into a **static blob** the app downloads.
2. **Per-user citation queries were always a privacy hole.** Asking OpenAlex or S2 "what happened
   to these 40 papers?" transmits the user's reading and skipping history to a third party.
   That contradicts the core promise. A static blob is not a fallback here — it is the correct
   design, and it fixes a leak the original document did not notice.

Rough size for a cs.*-only blob: ~500k papers × (id, count, velocity) ≈ 8 MB raw, ~3–4 MB
compressed. A monthly delta is small. D7's "escape hatch" becomes the primary mechanism.

## F3 — arXiv's own metadata carries a free, offline quality signal

The `arXiv` OAI metadata prefix exposes a `comments` field. For Proteina it reads:

> `ICLR 2025 Oral. Project page: https://research.nvidia.com/labs/genair/proteina/`

Measured across the same 120 March-2025 cs.LG papers, as the metadata stands today:

| Signal | Coverage |
|---|---|
| Has a `comments` field | 55% |
| **Names a known venue** (NeurIPS/ICML/ICLR/CVPR/ACL/…) or has `journal-ref` | **35%** |
| Acceptance language (accepted / to appear / oral / spotlight / camera-ready) | 19% |
| Both | 15% |

**35% coverage, zero API calls, no third party, works offline, and unambiguous** ("NeurIPS 2025"
needs no normalisation). Compare with citations, which are ~0% usable via OpenAlex and reach
maybe 10% of papers meaningfully even with perfect data.

**Confirmed at scale.** Re-measured on the 11,532-paper harvested corpus, split by age. The
signal accrues monotonically, which is exactly what the mechanism predicts and what the
Resurfacer needs:

| Age at measurement | Names a venue | Mean versions |
|---|---|---|
| < 1 month | 8.7% | 1.10 |
| 1–3 months | 9.9% | 1.18 |
| 3–6 months | 16.4% | 1.23 |
| **6–12 months** | **29.5%** | 2.48 |
| 1–2 years | 36.7% | 2.79 |
| 2 years+ | 46.0% | 2.85 |

By category: cs.CV 24.1%, cs.LG 18.8%, q-bio.NC 13.1%.

Two things worth noting. The 1–2yr figure (36.7%) independently reproduces the 120-paper
March-2025 sample above (35%) by a completely different sampling path — that sample was drawn
from the arXiv API by *submission* date and is unbiased, so it is the trustworthy anchor.
And **version count rises with age in lockstep** (1.10 → 2.85), so churn is available free from
the same harvest as a correlated second signal.

**Caveat, stated because it cuts against the result.** This corpus was harvested by OAI
*datestamp*, so the older bands are papers that were recently *updated* — and being updated
correlates with being accepted. Treat 6–12mo and older as mild over-estimates. The close
agreement with the unbiased March sample suggests the bias is small, but the honest single
number for "a paper you skipped 6–12 months ago" is **roughly a quarter to a third**, not half.

Better still, it arrives on exactly the Resurfacer's schedule: authors update the comments field
months later, when the paper is accepted. The mechanic survives intact — only the evidence
changes, from a number we cannot get to a fact we can:

> **You passed on this in March. It was just accepted to NeurIPS 2025.**

Arguably a stronger claim than a citation count, because it is a discrete human judgement rather
than a number needing age-normalisation and survey-filtering.

## F4 — arXiv has gaps; the daily-digest worker must expect empty days

The RSS feed for `cs.LG+cs.CV` today returned **HTTP 200, well-formed, zero `<item>` elements**.
Same for cs.LG, math.AP, q-bio.NC and astro-ph.GA individually — so this is not category-specific.

Cause: newest cs.LG submission in the API is **2026-09-04T17:59Z** (Friday, ~14:00 ET cutoff).
Sat/Sun have no announcements, and Monday 2026-09-07 was US Labor Day. Today is Tuesday and
there has been no announcement since Friday.

Consequences: the feed carries `<skipDays>` Sat/Sun, arXiv skips US holidays, and **a
zero-item feed is a normal state, not an error.** A worker that treats empty as failure will
retry-storm into the 429s the design already warns about. The end screen needs a real
"nothing announced today" state, and the digest should be able to draw from a backlog.

I could not verify that RSS carries full abstracts, because no non-empty feed existed today.
**Open item** — but not blocking: OAI-PMH is confirmed to carry abstracts and is the better path
anyway (see F5).

## F5 — OAI-PMH works well and is the right harvest path

`https://oaipmh.arxiv.org/oai?verb=ListRecords&metadataPrefix=oai_dc&set=cs&from=2026-09-01`
returned **3.7 MB / 1,300 records in a single request**, no key, no 429.

One gotcha: `from` filters on **datestamp (metadata modification), not submission date**. The
first record returned was arXiv 1601.04794 — a 2016 paper whose metadata was touched recently.
Filter on the real submission date after parsing.

That same property is a gift: harvesting by datestamp is exactly how you *detect* a paper whose
comments field just gained "NeurIPS 2025". F3's signal has a natural delivery mechanism.

## F7 — BibTeX import is title-matching, and the failure mode is silent

The first real library (`literatura.bib`, 104 entries) turned out to be three things stacked:
the Slovenian thesis template's boilerplate, the stock `xampl.bib` demo entries that ship with
LaTeX ("Handing out random pamphlets in airports", Knuth's TAOCP volumes), and ~52 real
research papers on diffusion, panoramic outpainting, super-resolution and texture synthesis.

**0 of 104 entries carried an `eprint` field or a DOI.** For a hand-written .bib, title search
is the only resolution path there is. D10 calls BibTeX import the highest-leverage onboarding
route; it should be designed assuming titles are all it gets.

Genuinely un-matchable entries are a large share and that is correct behaviour: Knuth's books,
`xampl.bib` jokes, Haralick 1973 (IEEE TSMC), Geman & Geman 1984 (PAMI), Efros & Leung 1999
(ICCV), Wang Tiles (SIGGRAPH 2003) — pre-arXiv or never-on-arXiv work. An importer that tried
harder on these would only manufacture false matches. Jaccard ≥ 0.6 against the arXiv title
rejected them cleanly, and every accepted match scored ~0.99.

**The bug worth recording.** The first batch run reported 22 papers as unmatched. Spot-checking
three of them — ControlNet, atrous convolution, InfinityGAN — each resolved at similarity
**1.00** on a direct query. They had not failed to match; the HTTP request had failed, and

```python
except Exception:
    return None
```

reported a network error as a product finding. Over ~100 sequential requests some transient
failures are certain. The fix is to retry with backoff and raise on persistent failure, so
"no such paper" and "the network broke" stay distinguishable. Any import match rate measured
without that distinction is an underestimate of unknown size.

## F6 — Landscape

- **Scholar Inbox** (ACL 2025 demo) is the closest prior art and independently validates the core
  design: content-based recommender, **logistic regression on user ratings**, per-user model,
  daily digest. It also solves two problems the same way the plan proposes — random papers as
  "easy negatives", and a weighted loss for feedback imbalance. It is a hosted web service with
  accounts. **Offline, on-device, no-account is the real differentiator**, not the ranking method.
  Encouraging: the hard part is known to work. Sobering: D1 is not novel, so it is not the moat.
- **Papers with Code is gone** — `paperswithcode.com/api/v1/` returns 302. Delete it from M5.
- **Hugging Face daily papers API is live** (HTTP 200, returns arXiv IDs + upvotes, current to
  2609.*). Small curated set, unofficial, no stability guarantee. Optional enrichment only.
- **SPECTER2** remains the domain-specific choice for scientific text (citation-based contrastive
  training); Qwen3-Embedding leads general MTEB but is far too large for a phone. E2 stands.

---

## What changes

| # | Original | Revised | Driver |
|---|---|---|---|
| D6 | OpenAlex for citations | **arXiv `comments`/`journal-ref` venue signal as primary; S2 static blob as secondary** | F1, F2, F3 |
| D7 | No backend; static blob as escape hatch | **Static blob is the design** — also closes a privacy leak | F2 |
| D5 | RSS, one request/day | **OAI-PMH by datestamp**; RSS optional. Empty days are normal | F4, F5 |
| M1 | "It now has 94 citations" | **"It was just accepted to NeurIPS 2025"** | F1, F3 |
| Onboarding screen 2 | 20 highly-cited papers via OpenAlex | Needs a new source — OpenAlex landmarks in cs.LG are journal articles, not what the field calls landmarks | F1 |
| E3 | Citation velocity formula | **Reframed**: does the venue signal beat citation velocity? | F3 |
| E4 | Measure the join | **Largely answered today** (92% by DOI) — and the answer is that the join is not the problem, the graph is | F1 |
| M5 | Papers with Code | Removed; dead | F6 |

## What survives untouched

E1 is unaffected and is still the experiment that decides whether the project happens. Nothing
found today touches the question of whether abstract embeddings separate *your* interests from
noise inside a narrow category. It remains the gate, and it is still the next thing to run.

D1 (embeddings + logistic regression) is independently validated by Scholar Inbox.
D2, D3, D4, D8, D9, D11, D12 are untouched.

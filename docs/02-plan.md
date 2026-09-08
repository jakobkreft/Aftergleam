# Plan — revised after the 2026-09-08 findings

Evidence in `01-findings.md`. This supersedes the Part 1 experiment list where they disagree.

## Phase 0 — data layer (DONE, 2026-09-08)

- [x] Verify arXiv OAI-PMH, RSS, legacy API, OpenAlex, Semantic Scholar live
- [x] E4 executed early: arXiv→OpenAlex join = 92% by DOI
- [x] E3 executed early and **failed**: OpenAlex citations are unusable for arXiv ML preprints
- [x] Venue signal discovered and measured: 35% coverage, free, offline
- [x] `aftergleam/harvest.py` — OAI-PMH harvester on `arXivRaw`, verified live

Phase 0 cost ~2 hours and killed a load-bearing assumption before any Kotlin existed. That was
the entire point of having a prototype phase.

## Phase 1 — E1, the gate

**Nothing else proceeds until this passes.** Unchanged by today's findings.

Does a linear model over abstract embeddings separate "interesting to me" from "not",
*within a single narrow category*?

- Corpus: ~20k cs.LG + cs.CV + q-bio.NC abstracts, last 18 months, via `harvest.py`
- Positives: the user's own library (see **Blocker** below)
- Negatives: random papers from the same categories ("easy negatives", the Scholar Inbox trick)
- Baselines in order: random → category-filter → **TF-IDF + linear SVM** → embeddings
- Metric: precision@10 on held-out positives mixed with 500 distractors
- **Critical control:** restrict to one narrow category. Cross-field separation is trivial and
  proves nothing.

**Pass:** precision@10 ≥ 0.5 within-category, and ≥15% relative over TF-IDF.
**Kill:** within-category precision@10 < 0.3 → abstracts don't carry the signal.
**If TF-IDF wins:** ship TF-IDF. No model download, ~10 MB app, milliseconds. That is a *better*
outcome, not a worse one.

### Ground truth — `literatura.bib`, 104 entries

Supplied 2026-09-08. It is a **hybrid file** and that shapes the experiment:

- ~30 entries are Slovenian thesis-template boilerplate (LaTeX guides, Knuth's TAOCP,
  citation instructions). These resolve to nothing on arXiv, which self-filters them.
- The remainder is a tight, coherent research bibliography: **diffusion models, panoramic
  and 360° image outpainting, arbitrary-scale super-resolution, GANs, VAEs**.

Consequences:

1. **The narrow-category control is cs.CV, not cs.LG.** The library is a computer-vision
   thesis bibliography. cs.LG remains in the corpus as a near-neighbour, and q-bio.NC keeps
   its role as the far-field contrast for E5's bridge test.
2. This is close to a worst case for E1, in a good way: every positive shares the diffusion
   /generative-imaging vocabulary, so the model cannot win on topic words alone. If
   separation works here, risk #1 is retired.
3. **0 of 104 entries carry an eprint field or a DOI.** Resolution is title-search only.
   That number is itself a product finding: D10 assumes BibTeX import is the highest-leverage
   onboarding path, and a hand-written .bib gives the importer nothing but titles to work with.
   The measured match rate is therefore the real import match rate, not a lab number.

## Phase 2 — E2 embedder bake-off

Only if E1 beats TF-IDF. Candidates: all-MiniLM-L6-v2, bge-small-en-v1.5, **SPECTER2**,
EmbeddingGemma. Measure precision@10, int8 size on disk, ms/abstract single-thread.
Pass: <120 MB quantized, <60 ms/abstract on laptop CPU.

### First measurements (all-MiniLM-L6-v2, ONNX Runtime, CPU)

Benchmarked on the real corpus through onnxruntime — the same runtime D3 targets — so these
project honestly rather than being flattered by a torch/GPU path.

| Config | Size | ms/abstract | 300 abstracts |
|---|---|---|---|
| fp32, 1 thread | 90.4 MB | 82.1 | 24.6 s |
| fp32, 4 threads | 90.4 MB | 31.8 | 9.5 s |
| **int8, 1 thread** | **23.0 MB** | 53.7 | 16.1 s |
| **int8, 4 threads** | **23.0 MB** | 22.1 | **6.6 s** |

Three consequences:

1. **The pass bar is met with room to spare.** 23 MB against a 120 MB budget, and the design's
   "roughly fifteen seconds for 300 abstracts" is realistic on a phone at int8 with threading —
   the daily digest is not compute-constrained.
2. **The onboarding download shrinks from ~100 MB to ~23 MB.** That materially changes the
   opt-in screen's tone, and F-Droid's stance on downloaded assets.
3. int8 is *faster and 4× smaller* here, so the only open question for quantization is
   precision loss — which E2 measures against E1's metric, not in the abstract.

Still to measure: SPECTER2 (may beat MiniLM on scientific text and is the reason E2 exists),
bge-small, and int8 precision loss. **And on-device**: a Pixel 10 Pro is available, so the
projection above should eventually be replaced by a real measurement rather than a multiplier.

## Phase 3 — E3-revised: the quality signal (replaces citation velocity)

The original E3 is dead as written. The replacement question:

**How much of "this paper mattered" is recoverable from arXiv metadata alone?**

- V1: venue in `comments`/`journal-ref` (35% coverage, measured)
- V2: version churn — `n_versions` now comes free from the harvester
- V3: Semantic Scholar citations, from the **bulk ODC-BY dataset**, not the API
- Compare all three against the user's own judgement of what was landmark in a field they know

Also decide the blob: size, update cadence, hosting, and the offline join.

## Phase 4 — E5 bridge, E6 exploration

Unchanged. Both are cheap and neither gates anything.

## Phase 5 — Android v1

Only after E1 and E3-revised. Decision log stands except D5, D6, D7 (see findings table).

---

## Revised architecture consequence

The static blob is now the design, not the escape hatch, and it improves the privacy story
rather than compromising it:

```
Device                                    Built offline, by us, monthly
------                                    -----------------------------
arXiv OAI-PMH  ──> daily digest           S2 bulk (ODC-BY) ──┐
arXiv metadata ──> venue + churn signal                      ├─> signals.blob (~3-4 MB)
signals.blob   ──> "what mattered"        arXiv metadata ────┘     hosted on GH Releases
```

No per-user query ever leaves the device. The original design had the app asking OpenAlex
"what happened to these 40 papers I skipped?" — which transmits the user's reading history to a
third party and contradicts the core promise. The blob removes that call entirely.

## Open items

- RSS abstract content unverified — no non-empty feed existed on 2026-09-08 (F4). Non-blocking;
  OAI-PMH is the better path regardless.
- Onboarding screen 2 needs a new landmark source. OpenAlex's top-cited in cs.LG are journal
  articles, not what the field calls landmarks. Candidate: venue-accepted papers from the blob.
- Confirm S2 bulk dataset size and update cadence before committing to the blob.

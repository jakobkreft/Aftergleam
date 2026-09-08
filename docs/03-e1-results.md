# E1 results — the gate passes

Run 2026-09-08. Ground truth: `literatura.bib`, 38 papers resolved to arXiv abstracts.
Corpus: 11,532 arXiv abstracts (cs.LG 6,389 / cs.CV 4,640 / q-bio.NC 503).

## Verdict

**E1 passes.** Abstracts carry enough signal to separate this user's reading from noise
*inside a single narrow category*, which was the project's stated kill criterion.

**Consequence for v1: ship TF-IDF, defer the embedder.** Not because TF-IDF is more accurate —
that is not established — but because it is not measurably worse and costs nothing.

## The metric, and why it changed

The original design specified precision@10 on a 20% holdout. With 38 positives (24 in the
cs.CV control) that holdout is 4–5 papers, so **precision@10 cannot exceed 0.4** and the
"p@10 ≥ 0.5" pass bar is arithmetically unreachable. The first run duly produced a
"failing" 0.313 — against a ceiling of 0.400, i.e. 78% of the maximum attainable score.

That is a broken estimator, not a failing model. Reporting it as a failure would have killed
the project on an artefact.

Replaced with **leave-one-out**, which has no ceiling and asks the product question directly:
*this is a paper you actually read — would it have surfaced in your daily ten?* Each positive is
held out in turn and ranked against N random papers; we report how often it lands in the top 10.

## Results (leave-one-out, hit@10)

Distractor pool era-matched to the positives (submitted ≤ 2025), pool = 301:

| Method | cs.CV control (n=24) | 95% CI | All categories (n=38) |
|---|---|---|---|
| random floor | 0.042 | — | 0.053 |
| **TF-IDF + logistic regression** | **0.875** | [0.68, 0.97] | 0.816 |
| MiniLM-L6 int8 + logistic regression | 0.792 | [0.58, 0.93] | 0.789 |
| MiniLM centroid (cosine) | 0.500 | — | 0.526 |

Median rank of a held-out real paper: **2 out of 301**.

## What is and isn't established

**Established.** Both trained methods sit far above the 0.033 random floor, with confidence
intervals nowhere near chance. Within cs.CV — maximum vocabulary homogeneity, every abstract
talking about diffusion and image synthesis — roughly 7 in 8 papers this user genuinely read
would appear in a ten-card daily digest. Risk #1 ("abstracts don't carry enough signal within a
narrow field") is retired for this user.

**Not established.** TF-IDF's edge over embeddings is 2 papers out of 24 (p = 0.45). The
ordering is *not* statistically resolved. The honest statement is that MiniLM does not beat
TF-IDF here, so the design's own tie-break applies: use the cheaper one.

## Confounds tested

**Era leakage — tested and rejected.** The positives have median year 2020; the corpus median
is 2026. TF-IDF could have been detecting vocabulary drift ("GAN"/"CNN" vs "foundation model")
rather than taste. Re-running with distractors restricted to ≤2025 left cs.CV hit@10 unchanged
at 0.875, and *lowered* MRR for both methods (0.714 → 0.565 for TF-IDF) — the era-matched
distractors are genuinely harder, and the result survived them. The signal is topical.

**Corpus leakage — excluded by construction.** Library papers are removed from the distractor
pool before scoring; otherwise a true positive would be counted as a false one.

## Limitations, stated plainly

1. **n = 24 in the control.** Every number here has a wide interval. This is one person's
   library, not a study.
2. **This library is unusually coherent.** It is a single thesis's bibliography on diffusion-based
   panoramic outpainting — far more topically concentrated than a working researcher with four
   or five live interests. TF-IDF thrives on exactly that lexical coherence. **A broader library
   is where embeddings would be expected to pull ahead, and that case is untested.**
3. **SPECTER2 was not tested** — it is trained on scientific text with citation-based contrastive
   objectives and is the main reason E2 exists. MiniLM losing to TF-IDF does not settle it.
4. Only 38 positives survived import, from ~52 real papers in the file.

## What this means for the app

If this result holds for a second, broader library, v1 needs **no embedder at all**:

| Dropped | Consequence |
|---|---|
| ONNX Runtime Mobile (D3) | no native libs, no 16 KB page-alignment problem |
| int8 model download (D2/D4) | no ~23 MB asset, no F-Droid download-consent screen |
| CPU/XNNPACK accelerator choice (D4) | moot |
| Model weights in DataStore (D12) | store TF-IDF vocabulary + coefficients, a few hundred KB |

The app becomes pure Kotlin plus SQLite, roughly 10 MB, with a ranker that retrains in
milliseconds and is *inherently* legible — P3's "because you starred X" chip can quote the
actual matching terms, not a cosine distance.

**This does not delete the embedder, it defers it.** Re-run E1 against a broader library before
committing. The `--method embed` path stays in the harness for exactly that.

## Reproduce

```bash
cd prototype
.venv/bin/python -m aftergleam.build_corpus --sets cs q-bio --frm 2026-05-01 --max 30000
.venv/bin/python run_e1.py --control cs.CV --seeds 15   # ceiling-limited p@10
# leave-one-out (the real metric) — see aftergleam/e1.py: report_loo
```

# Plan — status and next steps

Updated 2026-09-08, after the first working Android build. Evidence in `01-findings.md`
(data layer) and `03-e1-results.md` (the ranking gate).

---

## Where the project actually is

| Phase | Status | Outcome |
|---|---|---|
| 0. Verify the data layer | **done** | OpenAlex ruled out, venue signal found. `01-findings.md` |
| 1. E1, the ranking gate | **done, passed** | hit@10 = 0.875 in cs.CV. `03-e1-results.md` |
| 2. E2, embedder bake-off | **answered early** | MiniLM does not beat TF-IDF. Ship TF-IDF |
| 3. E3-revised, quality signal | **measured and shipped** | Venue from arXiv comments, ~30% at 6–12 months |
| 4. E5 bridge / E6 exploration | not started | Neither gates anything |
| 5. Android v1 | **core loop working** | See below |

Two experiments were answered ahead of schedule and one was killed. That is the prototype
phase doing its job: E3 as originally written (citation velocity via OpenAlex) is impossible,
and it cost two hours to find out instead of two months.

## What runs on the phone today

Verified end to end on a Pixel 10 Pro, not just compiled:

- Category onboarding, then a digest of 25 cards
- Fetch from the arXiv Atom API, cached to SQLite
- TF-IDF plus logistic regression, retrained on device
- Graded interest per paper, with the model's own prediction shown next to it
- Venue extracted from arXiv's comments field, workshops discounted and labelled
- Re-rank offline in seconds, with networking switched off, as a test
- Restores the same digest and the same explanations after a restart
- Correct under three-button navigation as well as gestures

10 unit tests, all verified as actually executing.

### Added since

- **BibTeX and RIS import**, ported from the prototype and driven end to end through the real
  system file picker. The Kotlin parser reads the same 104 entries from the same file as the
  Python version, which is asserted in a test rather than eyeballed. Offered both during
  onboarding and from the Tune screen.
- **Three-tab navigation**: Today, Saved, Tune.
- **Saved list**, so saving leads somewhere. Kept deliberately separate from rating.
- **Tuning screen**: digest size, venue weight, exploration rate, and a two-step model reset.
- **Daily worker.** Periodic-daily on unmetered network and charging, with exactly one
  notification and only when there is something new. Empty results are a success, not a retry,
  because weekends and US holidays legitimately produce nothing.

### Still missing from v1

- **Search.** Not started.
- **The bridge card** exists but rarely fires: candidates come only from subscribed categories,
  so there is usually nothing outside them to promote. It needs its own small query.
- **Dwell tracking** as a weak positive. Deferred deliberately; explicit ratings are working
  and implicit signals were what D8 warned against.

## Design decisions changed by using the thing

These came from actually looking at the app, and each replaced something the original
document specified.

**Ten cards was too few.** Now 25 and configurable. Finishability is preserved: the digest
still ends, and the end screen still marks it. Ten was chosen on the theory that a short
ritual is more repeatable; in practice it was not enough to be worth opening.

**Star, hide and save were three switches doing two jobs.** Replaced by one continuous
interest rating in 0..1 plus an orthogonal save flag. "Star" and "save" really were nearly
redundant: collapsing the judgement into a rating leaves "save" meaning only "come back to
this", which is a genuinely different intent.

**The model now shows its prediction and takes correction.** Each card displays the predicted
interest and a slider seeded at that value, so the gesture is *correct the machine* rather than
*fill in a form*. The buttons remain as shortcuts to 0.9 and 0.1. This is P3 and P4 made
concrete, and it costs nothing: cross-entropy takes soft targets directly, so graded ratings
needed no new model.

**Rated papers leave the pool.** They were dominating the top of the digest, because a model
scores its own training positives most confidently of all. A paper you have judged is finished
business.

**Fetching and re-ranking are separate operations.** Measured: arXiv's newest cs.CV submission
stayed at 2026-09-04 across a full day of polling, because announcements happen once per
weekday at 20:00 US Eastern. A second fetch the same day returns the same papers. So re-ranking
is local, instant and offline, and fetching is throttled to six hours.

**Quality multiplies interest instead of being added to it.** Additively, venue swamped
everything: with the model's confidence near 0.2 and an accepted paper contributing 0.35
outright, nine of the top ten cards were placed by venue and the digest was really "recently
accepted papers". As a multiplier the count went to zero of twenty-five, and a strong venue now
promotes a paper the user would want anyway without rescuing one they would not. There is a
test for exactly that.

**Easy negatives must not come from the candidates.** Sampling training negatives out of the
pool being ranked means a paper can be labelled a negative in the very run that scores it,
suppressing the best matches. Negatives now come from older cached papers.

---

## The magic layer, re-planned against what the data actually supports

The user's question was where the "everyone is reading this" idea stands. The honest answer
differs per feature, because F1 removed the source three of them assumed.

### M1 — The Resurfacer. Viable, reframed, not built

Still the headline feature, but the evidence changes from a number we cannot get to a fact we
can:

> **You passed on this in March. It was just accepted to NeurIPS 2026.**

Citations are unavailable (91% of cs.LG preprints show zero in OpenAlex even at eighteen
months). Venue acceptance is available, free, offline, and arrives on exactly the right
schedule, because authors edit the comments field when a paper is accepted.

What it needs: keep skipped papers, re-fetch their metadata periodically, and diff the venue
field. The harvester already detects this — OAI-PMH `from=` filters on metadata modification
date, so a paper whose comments just gained "NeurIPS 2026" shows up in a datestamp query.
Cost is one request per week.

### M2 — Catch-up mode. Blocked on a landmark source

"The 15 papers everyone assumes you have read" needs a citation-ranked corpus, which is the
thing F1 showed does not exist for ML preprints via OpenAlex. Options, in order of preference:

1. Build the static blob from Semantic Scholar's bulk dataset (ODC-BY, 2.4B citation edges).
   Proper fix, a few days of offline work, and it also serves onboarding screen 2.
2. Rank by venue acceptance plus age from cached arXiv metadata. Free and immediate, but
   "accepted at CVPR" is a much weaker landmark signal than "cited 4,000 times".

Not started. Option 2 is a reasonable v2 stopgap.

### M3 — Drift report. Viable and cheap, not built

Needs only centroid deltas and topic histograms over data already stored. Nothing blocks it.

### M4 — The LLM. Unchanged, still deferred

Optional download, v3 at the earliest.

### M5 — "Everyone is reading this". Viable and privacy-safe

**Hugging Face daily papers is live and verified** (HTTP 200, returns arXiv ids with upvote
counts, current). It is the best available proxy for what the field is paying attention to
right now, on the timescale where citations are useless.

The important point, which was not obvious: **fetching a public list leaks nothing.** The
privacy objection in F2 was to asking a third party "what happened to *these forty papers I
skipped*", which transmits the user's reading history. Downloading the same public list
everybody else downloads, then joining it locally against the cache, reveals nothing about the
user. So this feature is compatible with the no-backend promise in a way per-paper citation
lookups never were.

Caveats to design around: the endpoint is unofficial and undocumented, the list is small
(tens of papers a day) and skewed to LLM work, and it can vanish. Treat it as enrichment that
degrades to nothing, never a dependency.

Other signals from M5:
- **Version churn** already comes free from the harvester (`n_versions`), and rises with age
  from 1.10 to 2.85. Cheap to surface, meaning not yet validated.
- **Author priors** are free and CC0 but need the citation graph, so they inherit M2's blocker.
- **Papers with Code is dead**, `paperswithcode.com/api/v1/` returns 302. Removed.

### M6 — Small things. Unblocked

Expiring mutes, focus mode, aging queue, deadline awareness, encrypted export for Syncthing,
share card. None depend on anything that failed. Deadline awareness is now better founded: the
corpus shows the venue-acceptance wave directly, so the app can say "ECCV results just landed,
the digest is unusually competitive this week".

---

## Next, in order

1. **The Resurfacer**, now that there is a rating history and skipped papers to re-check.
   Weekly re-fetch of metadata for papers passed over 3 to 12 months ago, diffing the venue
   field. One request a week.
2. **A bridge query**, so the cross-field slot has candidates to work with.
3. **Search**, keyword against the arXiv API with local re-ranking.
4. **The drift report**, which needs only data already stored.
5. **"Everyone is reading this"**, joining the Hugging Face daily list locally.

## Open items

- Confidence tops out near 0.45 with three ratings. Diagnosed as honest uncertainty, not
  undertraining: the model separates cleanly at 200 epochs (0.67 vs 0.11) and more epochs make
  it slightly worse. Worth re-checking once a real user has fifty ratings rather than three.
- E1 passed on one user's library, n=24 in the control. It is evidence the approach works, not
  that it generalises.
- On-device embedder timings are still a projection from a laptop. A Pixel 10 Pro is available
  if the embedder is ever revisited.
- RSS abstract content remains unverified; no non-empty feed existed on 2026-09-08. Not
  blocking, since the app uses the Atom API instead.

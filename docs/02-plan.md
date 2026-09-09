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

### Added since

- **Backup export and restore.** Plain JSON, merge-on-restore, refuses a file from a newer
  version rather than half-applying it. This is the whole multi-device story: no account, no
  server, put the file in a synced folder. Round-trip is tested under Robolectric, because
  `org.json` and SQLite are stubbed in plain android.jar and would fail silently.
- **The Resurfacer** and **the attention signal**, both described below.

### Added since

- **A paper detail screen with an inline PDF reader.** Tapping a card opens the paper rather
  than throwing the user into a browser: the complete abstract, the same rating control as on
  the card, a link out, and the PDF rendered in place. Uses the framework's own `PdfRenderer`,
  so no dependency, no native blob and nothing to upset a reproducible build. PDFs land in the
  cache directory, which needs no storage permission and lets the system reclaim them.
  Downloads write to a temporary name first, so an interrupted transfer cannot leave a
  truncated file that later looks cached.
- **A configurable digest time and a notification switch.**

### Added since

- **Search.** D11's two-tier design: arXiv's keyword index for retrieval, then local
  re-ranking of the hundred results against the user's model. A single slider moves the
  ordering between "closest to the query" and "closest to me", and moving it reorders what is
  already fetched rather than re-querying arXiv. The UI says plainly that this is keyword
  search with personalised reordering, not semantic search over the archive.
- **A weekly metadata refresh**, which is what actually keeps the Resurfacer supplied. Venue
  acceptance arrives months after a paper is cached, so the copy on disk is stale for exactly
  the papers the feature is about. Only papers in the three-to-twelve month window that still
  lack a venue are refetched, a hundred identifiers per request, so a run is two or three
  requests a week.
- **LaTeX stripped from displayed titles.** arXiv titles are LaTeX source and were rendering
  as "Cylin-Painting: Seamless {360\textdegree} Panoramic Image", which reads as an app bug.

### Added since

- **The bridge card actually fires.** It could not before, for a structural reason: candidates
  came only from subscribed categories, so "the best paper outside your fields" was always
  chosen from an empty set. It now costs one extra request against a rotating set of
  neighbouring fields. Which neighbours matters — sampling the whole taxonomy uniformly
  returns things with no plausible connection, and the risk the design named for this feature
  was surfacing papers that share vocabulary rather than ideas. So the pool is a curated
  adjacency map (cs.CV to graphics, medical imaging, neuroscience) that rotates by day.
  Verified on device: `math.OC, outside your usual`.
- **The search slider reorders instantly.** It never re-queried arXiv, but it did refit the
  vectoriser and retrain the classifier on every pixel of a drag. Both components are already
  computed per hit at search time, so changing the balance is now a re-sort of a hundred items.

### Still missing from v1

- Nothing from the original v1 scope. What remains is polish and the v2 features below.
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

### M1 — The Resurfacer. Built and verified

Still the headline feature, but the evidence changes from a number we cannot get to a fact we
can:

> **You passed on this in March. It was just accepted to NeurIPS 2026.**

Citations are unavailable (91% of cs.LG preprints show zero in OpenAlex even at eighteen
months). Venue acceptance is available, free, offline, and arrives on exactly the right
schedule, because authors edit the comments field when a paper is accepted.

Built. At most one card per digest, framed as discovery, and dismissible with "still not for
me", which is itself recorded as a strong negative. Workshop acceptances are excluded: a
workshop is not the "this turned out to matter" moment the feature promises.

Verified on device by injecting a digest entry dated five months back for a paper that had
since gained a venue. It produced exactly the intended card:

> You passed on this in April. UniMate: One Unified Model to Animate Diverse Skeletons.
> It was accepted to SIGGRAPH 2026.

It will fire naturally once there are three months of real history. Still to add: a weekly
re-fetch of metadata for old papers, so venues that appear after a paper leaves the cache are
picked up. OAI-PMH `from=` filters on metadata modification date, which is exactly the query
for this, at one request per week.

### M2 — Catch-up mode. Blocked on a landmark source

"The 15 papers everyone assumes you have read" needs a citation-ranked corpus, which is the
thing F1 showed does not exist for ML preprints via OpenAlex. Options, in order of preference:

1. Build the static blob from Semantic Scholar's bulk dataset (ODC-BY, 2.4B citation edges).
   Proper fix, a few days of offline work, and it also serves onboarding screen 2.
2. Rank by venue acceptance plus age from cached arXiv metadata. Free and immediate, but
   "accepted at CVPR" is a much weaker landmark signal than "cited 4,000 times".

Not started. Option 2 is a reasonable v2 stopgap.

### M3 — Drift report. Built

Sits after the end card, because it is a reflection on the week rather than another thing to
get through. Compares the last fortnight of ratings against the one before it and reports
topics rising and falling, authors the user keeps returning to, and how exploration fared.

Three details that decide whether it reads as true:

- **Shares, not counts.** A fortnight of simply reading more would otherwise register as
  every topic rising at once.
- **"Not enough history yet"** rather than a trend invented from four papers. Verified on
  device, where two days of history correctly produces exactly that.
- **The exploration line counts only cards the user actually rated.** The first version
  counted every card shown and duly announced "none of the 10 exploration cards landed" when
  in truth none had been judged at all. Scoring an unrated card as a failure is the same
  error as treating everything scrolled past as a negative, which D8 exists to avoid.

The last of those is the honest-miss line the design asked for, and it is worth keeping
uncomfortable: an app that only ever reports success is one you stop believing.

### M4 — The LLM. Unchanged, still deferred

Optional download, v3 at the earliest.

### M5 — "Everyone is reading this". Built

**Hugging Face daily papers is live and verified** (HTTP 200, returns arXiv ids with upvote
counts, current). It is the best available proxy for what the field is paying attention to
right now, on the timescale where citations are useless.

The important point, which was not obvious: **fetching a public list leaks nothing.** The
privacy objection in F2 was to asking a third party "what happened to *these forty papers I
skipped*", which transmits the user's reading history. Downloading the same public list
everybody else downloads, then joining it locally against the cache, reveals nothing about the
user. So this feature is compatible with the no-backend promise in a way per-paper citation
lookups never were.

Built and verified on device: a card showed "widely read today, 378 upvotes". The score is
log-scaled and capped, because the list spans roughly 3 to 300 upvotes and a linear scale
would let one viral paper dominate an entire digest. It multiplies interest exactly as venue
does, so it cannot rescue a paper the user would not want.

The endpoint is unofficial, the list is small and skewed towards language models, and it can
vanish. A failure returns an empty map and the digest is built exactly as it would have been.

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

1. **Release engineering**: signing config from env or gradle properties, fastlane metadata,
   and a reproducible-build check. Release builds compile; nothing has been shipped.
2. **Catch-up mode**, still blocked on a landmark source. See M2.
3. **Does the bridge survive contact with reality?** This was open question 4 in the original
   design and is now answerable: the slot fires, so rating the cards it produces over a few
   weeks will show whether cross-field suggestions are interesting or merely word-matched.
4. **Real-world use.** Every measurement so far comes from one library and one device, and
   several features now say "not enough history yet" because that is the truth. The next
   useful data is a fortnight of actual daily use: it is what makes the drift report, the
   resurfacer and the bridge answerable rather than merely built.

## Open items

- The daily worker fetches papers but does not compose a digest, so `shown` records only the
  days the app was opened. Catch-up works around this by ranking what was never shown, which
  is arguably better than a digest nobody read, but it does mean there is no record of what a
  missed day would have contained.
- Dwell timers run while the app is in the background, so a paper opened and left on screen
  during a phone call earns its 15 seconds. One `DWELLED` at 0.4 is cheap enough that
  lifecycle-aware timers are not worth the machinery yet; worth revisiting if the pattern
  shows up in real use.
- Confidence tops out near 0.45 with three ratings. Diagnosed as honest uncertainty, not
  undertraining: the model separates cleanly at 200 epochs (0.67 vs 0.11) and more epochs make
  it slightly worse. Worth re-checking once a real user has fifty ratings rather than three.
- E1 passed on one user's library, n=24 in the control. It is evidence the approach works, not
  that it generalises.
- On-device embedder timings are still a projection from a laptop. A Pixel 10 Pro is available
  if the embedder is ever revisited.
- RSS abstract content remains unverified; no non-empty feed existed on 2026-09-08. Not
  blocking, since the app uses the Atom API instead.

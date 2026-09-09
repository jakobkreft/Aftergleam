# Rethinking the core: a recommender that behaves like one

The app proves the concept. The ranking underneath it is a first draft that borrowed nothing
from how recommenders are actually built, and three of its assumptions are wrong. This is the
design for replacing it.

---

## What is wrong now, precisely

### 1. The rating slider asks a question nobody can answer

It demands a *calibrated number* for a judgement people hold as a vague feeling. Three
separate failures:

- **No shared scale.** One reader's "quite interested" is 95%, another's is 55%. The same
  number means different things per person, and the model treats them identically.
- **The UI anchors the answer it is measuring.** Showing "model predicts 30%" next to the
  slider invites the reader to nudge to 25% rather than state a belief. That is not a
  measurement, it is the model marking its own homework.
- **It punishes engagement.** Reading a paper properly gives you more reasons to find fault,
  so the more attention a paper gets the harsher its rating. The signal is anti-correlated
  with the behaviour the app wants.

And it is *work*. Most people will not do it, which leaves the model starving.

### 2. Implicit signals, the honest ones, are thrown away

Opening a paper, dwelling on the abstract, downloading the PDF, reading six pages of it: all
free, all far more truthful than a slider, and all currently discarded.

The original design forbade implicit signals (D8). That was right about **negatives** and
wrong about **positives**. Everything not clicked outnumbers everything clicked roughly thirty
to one, and treating that as rejection does collapse a feed. But a download is not ambiguous.

### 3. The model narrows far too fast, and the serving layer makes it worse

Logistic regression on a handful of positives against random negatives produces a confident,
narrow boundary. Then the digest takes the **top K deterministically**, so the reader only
ever sees the middle of that region, rates more of it, and the boundary tightens. Textbook
feedback collapse.

Two compounding mistakes:

- **Confidence is not discounted for thin evidence.** Six ratings produce predictions as
  sharp as six hundred would.
- **argmax instead of sampling.** Deterministic top-K wastes the tail of the distribution and
  means re-ranking with no new data returns exactly the same list, which is also why "show me
  more" cannot work today.

### 4. Work is repeated for no reason

Pulling to refresh twice in ten minutes issues the same arXiv query for the same papers.
arXiv announces once per weekday; a second fetch inside that window is pure waste and pure
rate-limit risk. Similarly, re-ranking with no new ratings and no new papers recomputes an
identical answer.

### 5. 300 fetched, 25 shown, 275 discarded

The finishable digest is the right *default*, not the right *only*.

---

## The framework

The shape every large recommender converges on, because the alternatives do not work:

```
  sources ──▶ candidates ──▶ scoring ──▶ policy ──▶ sampling ──▶ surface
                                 ▲                                  │
                                 └──────── signals ◀────────────────┘
```

Four stages, each with one job. The current app collapses scoring, policy and serving into a
single `sortedByDescending`, which is why it has no room for exploration.

### Stage 1 — Signals: many, weighted, mostly implicit

Replace the single rating with an **engagement ledger**. Each interaction appends an event;
the model trains on an aggregate.

| Event | Weight | Reasoning |
|---|---|---|
| Explicit "more like this" | **+1.0** | The ceiling. Unambiguous. |
| Read 3+ PDF pages | +0.9 | Costly and deliberate |
| Shared | +0.85 | Endorsement to a third party |
| Downloaded the PDF | +0.7 | Real commitment, one tap past curiosity |
| Saved for later | +0.6 | Intent, weaker than action |
| Dwelled 20s+ on the paper | +0.4 | Read the abstract properly |
| Opened the paper | +0.25 | Curiosity, cheap, still information |
| Shown and passed over | **−0.05** | Kept tiny on purpose, see below |
| Explicit "not for me" | **−1.0** | The floor |

A paper's training label is the **strongest positive event it earned**, not a sum: opening,
downloading and reading one paper is one endorsement, not three.

**The skip signal is the dangerous one.** It is admitted at 1/20th the weight of a like, only
counted for cards that were actually on screen long enough to be judged, and the *total mass*
of skips is capped at the total mass of positives during training. Without that cap D8's
thirty-to-one imbalance reappears and the feed strangles itself.

**What replaces the slider.** Two buttons: *more like this* and *less like this*. That is a
steering instruction, not a measurement, so there is no scale to calibrate and nothing to
anchor. Everything between them is inferred from what the reader actually does.

### Stage 2 — Scoring, with confidence that reflects the evidence

Keep TF-IDF and logistic regression: E1 earned that, and it stays legible.

Add **shrinkage toward the prior**:

```
p_final = w·p_model + (1 − w)·p_prior       where  w = n / (n + k)
```

with `n` the number of distinct papers with a signal and `k` around 25. At six signals the
model carries a fifth of the weight; at a hundred it carries four fifths. This is the direct
fix for "far too confident far too early", and it costs one line.

### Stage 3 — Policy: allocate slots to topics, not just papers

Item-level noise still samples from inside the model's comfortable region. Collapse has to be
fought one level up.

Cluster the day's candidates into topics (cheap: primary category plus top TF-IDF terms).
Treat each topic as an arm of a bandit, holding a Beta posterior over "does this reader engage
with this topic". Allocate digest slots by **Thompson sampling**: draw once from each topic's
posterior, give the slot to the highest draw.

Why this is the right tool: a topic with three engagements out of four has a *narrow* posterior
near 0.75, while a topic never tried has a *wide* one. The wide one wins a slot regularly,
purely because it is uncertain. Exploration stops being a number the user has to set and
becomes a consequence of what the model does not yet know.

### Stage 4 — Sampling, not argmax

Turn scores into a distribution and draw the digest **without replacement**, Gumbel-top-k:

```
key_i = log(p_i)/T + Gumbel(0,1)      take the k largest keys
```

Temperature `T` is the one exploration control worth exposing, and it can be described
honestly: *how often to show you something the model is unsure about*.

This answers the question directly: **re-running the sampler with no new data gives a
different digest**, because it is a fresh draw from the same distribution. "Show me more"
becomes meaningful, and so does re-ranking.

### Stage 5 — Surfaces

- **For you** — the finishable daily digest. Low temperature, topic-allocated, ends.
- **Explore** — high temperature, maximum topic spread, drawn from the candidates the digest
  did not use. This is where the other 275 papers live, and it may scroll.
- **Popular** — no personalisation at all: Hugging Face upvotes and venue acceptances. A
  reader wants this some days, and it is the honest place for it, mixed into a personalised
  feed it would just be noise.
- **Library** — saved, offline, rated, history.

Search moves to an action in the top bar; it is a verb, not a place.

---

## Doing less work

**Fetching.** Record the newest submission date seen. arXiv announces once per weekday around
20:00 Eastern, so if the clock has not crossed an announcement boundary since the last fetch,
a new request returns the same papers: skip the network entirely and re-sample instead. Pull
to refresh then still *feels* live, because the digest genuinely changes, without touching
arXiv.

**Training.** Cache the fitted model against a hash of the signal ledger. Unchanged ledger,
no retraining.

**Serving.** Re-sampling from a cached model is microseconds. This is what makes "show me
more" free.

---

## Built so far

Stages 1 to 3 are in, and the tests for them are the interesting part.

**The signal ledger** replaces the slider. Opening, saving, downloading and reading past the
third page are all recorded; the label for a paper is its strongest event, not a sum. Existing
ratings migrated: a slider value above the midpoint became "more like this", below it became
"less like this", and the exact number was discarded because it was never comparable between
readers anyway. On the test device 58 ratings became 57 likes, 1 dislike, 13 opens and 5 saves.

**Shrinkage** pulls a prediction toward the prior in proportion to how little evidence stands
behind it. A 0.95 from six signals lands below 0.5; from a hundred it stays above 0.7.

**Sampling replaces argmax**, and this took two attempts. Sampling a pool and then running a
greedy diversity pass over it does not work: whenever the pool covers the candidates the
greedy pass decides everything and the digest is identical on every re-rank, and shrinking the
pool far enough to matter starves the diversity pass of the minority topics it exists to
rescue. Both are now one loop, where each pick is *drawn* from the diversity-adjusted scores
rather than taken as the maximum.

Two things fell out of this that are worth recording:

- **Shrinkage broke an invariant, and the fix was to state it properly.** Compressing
  relevance toward the prior narrows the gap between an on-topic and an off-topic paper enough
  that the venue multiplier can flip them. That is wrong at a hundred signals and defensible
  at three, so the test now asserts "a strong venue cannot rescue an uninteresting paper" at a
  realistic evidence level, and a second test documents that at three signals venue is
  *allowed* to win.
- **The bridge slot had to be reserved before sampling.** A sampler will happily draw the one
  out-of-field paper into an ordinary slot and leave the bridge step with nothing.
- **The diversity pass was quietly quadratic, and a bigger digest exposed it.** Its cost is
  slots x pool x already-chosen. At a digest of twenty-five that is about twenty thousand
  sparse cosines and nobody notices; at sixty it is three hundred thousand, and the app sat on
  "Ranking" burning 182% CPU for minutes. Two caps fix it: a fixed candidate pool, so cost no
  longer grows with digest size, and comparing each candidate only against the last dozen
  picks, since near-duplicates cluster and a paper echoing something chosen forty slots ago is
  not what the pass is for.

## Stage 4: the topic bandit

Slots are now shared out across topics *before* any paper is chosen. A topic is the paper's
primary arXiv category, which is stable, already stored and the right granularity for deciding
how much of a morning an area deserves. Each carries a Beta posterior over "does this reader
engage with this", and slots go to whichever topic wins a Thompson draw.

The width of the posterior does the exploring. A topic tried four times with three
engagements sits narrowly around 0.75; a topic never tried is flat across the whole range and
so wins slots regularly, purely because nothing is known about it. That is the property
per-item sampling cannot provide: item-level noise only ever reshuffles the papers inside the
region the model is already confident about.

On the device the difference is visible: **sixty papers across thirteen topics**, with cs.CV
and cs.LG still dominant as the history warrants, and real representation for robotics,
language, signal processing and optimisation.

Two things the tests settled, both of which corrected me rather than the code:

- **A consistently ignored topic should stop appearing.** I first asserted that every topic
  keeps a foothold; it does not, and it should not. Forty ignores out of forty is strong
  evidence, and a recommender that kept serving that anyway would not be listening.
- **Certainty has to be bounded even so.** Without a cap the posteriors sharpen without
  limit, and a topic abandoned long ago becomes unrecoverable: no amount of renewed interest
  moves a Beta(1, 201). Interests are not stationary, so the counts are scaled down to a
  window that keeps the conclusion and discards the excess certainty.

**Still missing, and the tests say so explicitly:** the window bounds certainty but preserves
the *ratio*, so it does not on its own resurrect a topic whose engagement rate is genuinely
poor. Weighting recent evidence above old evidence is the piece that would, and it is the
natural next refinement.

## Stage 5: not doing the work twice

**Fetching now asks the schedule, not a timer.** arXiv announces once per weekday evening at
20:00 US Eastern, Sunday through Thursday, so between announcements the same query returns the
same papers. Pulling to refresh twice in ten minutes used to issue two identical requests.

Knowing the schedule beats a fixed interval in both directions: a six-hour timer refetches
four times a day for nothing and can still sit an hour stale straight after an announcement.
This fetches exactly once per announcement, and over a Friday-to-Sunday weekend it makes no
requests at all, which the tests check explicitly because that is the case a naive interval
gets most wrong.

Checked against the real clock on the device: last fetch 06:04 Eastern, last announcement
20:00 the previous evening, so the network was correctly skipped.

**The model is kept between rebuilds.** Training is most of what a rebuild costs and it
depends only on the reader's signals, so re-fitting it because today's papers arrived is work
for nothing. The cache is keyed on the ledger, so any new reaction drops it immediately, and
the easy negatives are now drawn with a seed derived from the ratings: the same ledger always
trains the same model, so caching cannot quietly change what the reader sees.

**Scope, honestly.** The cache lives for the session, so it saves a re-rank or a pull to
refresh, not an app restart. That is less of a gap than it sounds, because reopening the app
restores the day's digest from the database and never trains at all. The nine seconds are paid
once a day, and the unpersonalised first pass covers them.

## Making it fast

The digest took 73 seconds to build, and the fix was not the algorithm.

**Boxing was costing 8x.** Sparse vectors were `Map<Int, Float>`, which boxes both the key
and the value, and training walks every feature of every document once per epoch: two hundred
epochs over roughly eight hundred documents is tens of millions of boxed lookups, plus a fresh
gradient `HashMap` allocated per epoch. Replacing them with two primitive arrays, a sorted
`IntArray` of indices and a `FloatArray` of values, and reusing one dense gradient buffer took
the same work from **73 seconds to 9**.

Two smaller ones found on the way:

- `termAt`, which maps a feature index back to a word for the "why" chip, was a linear scan of
  the vocabulary. Three calls per candidate over four hundred candidates against a forty
  thousand word vocabulary is tens of millions of comparisons for cosmetic text. It is now an
  array lookup.
- The diversity pass is `slots x pool x already-chosen`, which was fine at a digest of
  twenty-five and quadratic misery at sixty. A fixed pool and a bounded look-back cap it.

**And the remaining wait is now hidden.** The ranking that needs no model, newest first with
accepted venues promoted, costs nothing and is a perfectly reasonable digest on its own. It
goes on screen immediately, labelled "newest first, still learning your taste", and the
personalised ranking replaces it when training finishes. The reader is on the first card
either way, and the label says plainly what is happening rather than showing a spinner.

## Order of work

1. ~~Signal ledger and the two-button control.~~ Done.
2. ~~Shrinkage.~~ Done.
3. ~~Sampling in place of argmax.~~ Done.
4. ~~Topic bandit.~~ Done. See below.
5. ~~Fetch and training skips.~~ Done. See below.
6. **Surfaces**: For you, Explore, Popular, Library.

Stages 1 to 3 are worth doing together, since they change the same code path. Stage 4 is
separable. Stage 6 is a UI change resting on all of it.

## What stays

E1 still holds: abstracts carry enough signal, and TF-IDF matched a neural embedder on the
one real library tested. None of this replaces the ranker; it replaces everything around it.
The venue signal, the resurfacer and the arXiv-only data path are all unaffected.

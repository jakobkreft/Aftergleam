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
| Explicit "more like this" | **+0.95** | An instruction, not a measurement |
| Read 3+ PDF pages | +0.9 | Costly and deliberate |
| Shared | +0.85 | Endorsement to a third party |
| Read the PDF for 20s+ | +0.7 | Real commitment, well past curiosity |
| Saved for later | +0.6 | Intent, weaker than action |
| Stayed 15s+ on the paper | +0.4 | Read the abstract properly |
| Opened the paper | +0.25 | Curiosity, cheap, still information |
| Shown and passed over | **-0.05** | Reserved, not emitted. See below |
| Explicit "not for me" | **-1.0** | The floor |

**Corroboration, not a sum and not a maximum.** The label was originally the strongest event
alone, which is right about the danger and wrong about the evidence: under a maximum, saving
a paper *and* asking for more like it says exactly what asking on its own says, and a paper
someone downloaded, read four pages of and saved is indistinguishable from one they merely
read. The strongest signal now sets the floor and the rest close half the remaining gap to 1,
by noisy-OR. It is bounded above by 1 whatever happens, which is what the no-sums rule was
actually protecting: a single enthusiastic afternoon still cannot outweigh a month of
judgements. Measured on 84 papers of real history, bare likes moved 1.0 to 0.95, like plus
save plus open rose to 0.968, and downloaded plus read plus opened rose to 0.939.

**Weights above "saved" are gated on time, not on taps.** Downloading used to score 0.7 the
instant the reader tapped Read, so bouncing straight back out of a PDF was worth the same as
reading it. Cost is what justifies those numbers, and cost includes time: the dwell signal
fires at 15 seconds on the paper, and the download signal at 20 seconds with the PDF actually
on screen. Both are timers armed on open and cancelled on leaving, rather than a stopwatch
read on the way out, because the way people leave a screen is by swiping home or killing the
app, and a stopwatch would record nothing in exactly the cases where the reader was most
absorbed. Verified on device: a glance recorded `OPENED` alone, a 15-second read added
`DWELLED`, and the PDF signal appeared at 20 seconds and not at 8.

**The skip signal is the dangerous one, and is deliberately dormant.** Nothing emits it.
Not-engaged-with is already used where it is safe, as the `ignored` count in the topic bandit,
where thirty-to-one is a ratio rather than thirty times the training mass. Feeding the same
observation to the classifier as well is the part that would reproduce D8's imbalance and
strangle the feed. The weight and the mass cap stay defined for whenever there is a
measurement saying otherwise; until then the honest description is that skips steer how much
of a topic gets shown and do not train the model.

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

## Stage 6: four surfaces

The app had four tabs, one of which was Search. That was the wrong shape: search is a verb,
not a place, and nobody opens an app in order to be in search. It is now an action in the bar
alongside settings, which frees the tabs for the four things a reader actually returns for.

- **For you** — the digest. Finishable, and unchanged.
- **Explore** — everything the digest passed over. High temperature, raised diversity, and it
  does not claim to end. This is the answer to "three hundred fetched, sixty shown, where are
  the rest": they were being discarded, and now they are a surface.
- **Popular** — the model switched off entirely. Ordered by attention and venue only, so it
  says the same thing to every reader. Mixed into a personalised feed this is noise; kept
  apart it answers a question people genuinely ask.
- **Library** — saved, offline, rated.

Explore earns its place immediately. For a reader whose history is diffusion and computer
vision it opened with robotics, hyperbolic geometry from a maths-and-art proceedings, drum
gesture mappings and medical super-resolution: genuinely outside the model's comfortable
region, which is the whole point, and nothing a purely score-ordered feed would ever surface.

**One honest limitation.** Popular depends on the Hugging Face upvote list, which is fetched
along with the digest and held in memory. After a restart with no fetch due, it falls back to
ranking by venue alone. Persisting the counts would fix it and is a small change; the surface
is useful either way, and its own subtitle says what it ranks by.

## Interface corrections, from using it

**The two-phase digest was a mistake and is gone.** Publishing an unranked digest and
reordering it a few seconds later is worse than waiting: a list you have begun reading
rearranging itself is disorienting in a way a short pause is not. Now that ranking takes about
ten seconds rather than seventy, the wait is shown as pulsing skeleton cards in the shape of
the real ones. If ranking ever grows past about thirty seconds the earlier trade would become
right again, but not at ten.

**The predicted percentage is off the cards.** "38%" looks precise and is not: a paper at 38
can be exactly right and another at 38 useless, and printing it invites trust the model has
not earned. It remains what it always was, an internal quantity for *ordering*, which it is
good at. The reader gets the reason instead, in words they can check.

**One card, used everywhere.** The digest, explore, popular and search had each grown their
own, so a paper you could react to in one place was inert in another for no reason a reader
could see. Popular passes no reason line, because that surface is explicitly not personalised
and inventing one would undercut the only thing it promises.

**Active actions now sit on a filled chip.** A tinted outline was too subtle: a saved paper
looked much like an unsaved one, so the obvious next tap un-saved it by accident.

**The library's rating sliders are gone.** They were left over from the rating concept that
was removed, and a control that exists nowhere else is worse than no control.

**Search has a scope.** "Which paper did I save last week" is a different and more common
question than "what exists on arXiv": it needs no network, and sending it to arXiv would
usually fail to find the very paper meant. Three scopes: all of arXiv, everything this device
has seen, and only what was saved or reacted to.

**Search matched nothing, and the cause was subtle.** Query similarity was computed with the
standard vectoriser, which drops terms appearing in more than half the documents. That is
right for a corpus and exactly wrong for a result set: these documents were returned *because*
they match the query, so the query's own words were in most of them and were being filtered
out. Every result scored a query match of zero, which also made the personalisation slider
useless, since one side of it was always nothing.

## Search was retraining the model on every query

Searching a hundred cached papers took about as long as searching the whole of arXiv, which
is the kind of symptom that says the network is not the bottleneck.

It was not. Every query fitted a fresh TF-IDF vocabulary and trained a fresh logistic
regression, which is the same work the digest does and the reason a rebuild costs seconds. It
also loaded eight hundred papers with their abstracts out of SQLite purely to sample negatives
for that training. All of it duplicated a model the app had already trained and cached.

The interest model depends only on what the reader has reacted to, so there is no reason for
search to have its own. It now takes the cached one and needs no negative pool at all.
Measured on device, a second search in the same session went from **9.7 seconds to 3.3**, and
most of what remains is the measurement harness rather than the app.

The first search of a session can still pay for training if the digest has not been built yet.
In practice the digest builds first and the model is already warm.

**Two smaller things from the same pass.** "Saved and reacted to" was accurate and unreadable
in a row of chips; the scopes are now arXiv, On device and My library. And reaching for search
from the library is almost always "where did I put that paper" rather than "what else exists",
so it opens with that scope already selected. A default, not a rule: the chips are right there.

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
6. ~~Surfaces.~~ Done. See below.

Stages 1 to 3 are worth doing together, since they change the same code path. Stage 4 is
separable. Stage 6 is a UI change resting on all of it.

## What stays

E1 still holds: abstracts carry enough signal, and TF-IDF matched a neural embedder on the
one real library tested. None of this replaces the ranker; it replaces everything around it.
The venue signal, the resurfacer and the arXiv-only data path are all unaffected.

## Time-decayed evidence, and attention that survives a restart

Two gaps closed after the bandit had been running for a while.

**The bandit could not be talked out of a conclusion.** `EVIDENCE_WINDOW` bounds how sharp a
topic's posterior may get, which stops a topic becoming *unrecoverable*, but it preserves the
ratio, so it cannot revive a topic whose rate is genuinely poor. Two hundred ignores from last
spring against a dozen engagements this month is six percent, and the bandit was right to call
that poor and wrong about what it was answering: the reader has changed project, and only the
recent part of that history is about them.

Each paper now contributes to its topic by the day it was last shown, discounted by a
half-life of thirty days. Exponential rather than a cutoff, so a topic's standing never lurches
on the day an old observation falls off the end. Thirty days is chosen against the app's own
rhythm rather than a sweep: the digest arrives daily, so it is roughly one working cycle of a
project, long enough that a fortnight away from a field does not erase it. Tested in both
directions, because forgetting everything on a schedule is just periodic amnesia: a topic
ignored heavily six months ago and read again this week comes back, and one declined
repeatedly this month stays gone.

**Popular was quietly lying on cold starts.** Hugging Face upvote counts lived only in the
view model, so the tab was correct exactly once per fetch and degraded to venue matches alone
on every launch where no fetch was due, which is most weekends, with nothing on screen saying
so. They are now a table, loaded before the first frame, replaced wholesale on each fetch and
pruned at thirty days. Confirmed on device: force-stop, relaunch with no fetch due, and the
tab still ranks by attention with the Hugging Face attribution on the cards.

## The ledger had two halves that never met

Reported from use: after onboarding from scratch, the library's reacted shelf listed the
survey answers with neither chip lit, and reacting to papers in the digest never made the
count grow.

Both were the same defect. The signal ledger replaced the rating column, but the column stayed
behind, and the code split down the middle: onboarding, library import, backup restore and the
resurfacer all wrote judgements to `reactions.interest`, while the model, the library and the
chips all read the ledger. Nothing failed. The two halves simply addressed different tables.

What that cost, beyond the two visible symptoms:

- **Onboarding taught the ranker nothing.** Twenty survey answers, zero training examples. The
  only reason any of this worked was the one-off migration that copied the column into the
  ledger when the ledger was introduced; every judgement written afterwards was invisible.
- **Importing a library taught it nothing either**, which was meant to be the fastest way in.
- **Backups carried saves and no judgements.** A year of reactions would have silently failed
  to arrive on a new phone.
- **"Reset the model" reset nothing the model reads.** It cleared the reactions table and left
  the entire ledger in place, so the next digest ranked exactly as before.
- **The drift report and the exploration outcome** were empty for anyone who had only ever
  used the buttons, and the resurfacer offered back papers the reader had already engaged with.

Everything now reads and writes one store. The rating field is deleted from the `Reaction`
class rather than merely abandoned, so there is no second place left to write and the compiler
enforces it; the column survives in the database only for the migration that reads it once.

**A third, smaller version of the same confusion.** The header claimed "ranked from N papers
you have reacted to" using a count that included the seed documents built from the chosen
subjects. Those are synthetic text the ranker legitimately fits on, not papers anyone reacted
to, so a reader who had judged nothing was told they had judged two, and the model switched
itself on one reaction early.

Verified by wiping the device and onboarding from scratch: six survey answers produced four
ledger rows and no rows in the old column, the shelf showed four papers with three More and
one Less lit, and reacting to two more in the digest took the shelf to six.

## A session of just using it

Findings from driving the app rather than testing a change, and what each turned out to be.

**Three screens, three different numbers, one word.** The digest header said "87 papers you
have reacted to", the end card said "87 rated", settings said "87 papers reacted to", and the
library shelf two taps away said 62. Every number was correct: the first three counted every
paper carrying any signal, including the merely opened, and the fourth counted button presses.
They are different claims and now say so, with a separate count for each and wording that
distinguishes learned-from and reacted-to.

**Explanations naming nothing.** A card read "matches layers, arbitrarily, terms". The
vectoriser's stopword list is deliberately short because words like "not" and "without" carry
method meaning, and that is still right for ranking; the mistake was reusing it for captions.
Explanations now skip generic research vocabulary, chosen separately from the model's
vocabulary so that fixing a caption cannot change a ranking. Measured over a rebuilt digest:
0 of 60 explanations name filler, against a sample that previously produced "arbitrarily" and
"terms" in the top three of a card.

**Two surfaces still spun.** For You got skeleton cards when the two-phase digest was removed;
Explore and Search kept bare spinners, which is the appearance the skeletons exist to avoid.
Search waits longest of the three, twelve to fifteen seconds against arXiv's rate limit, and
was the one saying least. Both now show placeholder cards and a line naming the wait.

**The offline message was the exception's.** Tapping Read with no network produced
`Unable to resolve host "arxiv.org": No address associated with hostname` on a blank screen.
For an offline-first reader, being offline is the expected case and the only one with an
obvious next step, so it now says so in a sentence and points out that what is downloaded
still works.

**Checked and found fine**, worth recording so they are not re-investigated: the reset control
does have a confirmation step; the library crash reported when offline no longer reproduces;
the library-scope search works with no network; Popular survived a cold start with no fetch.

## Search that answers before the network does

An arXiv search measured twelve to fifteen seconds on the device, nearly all of it the wait
for a server on a one-request-every-three-seconds budget. But a reader looking for something
they have already read does not need that server: the papers are in SQLite. The device now
answers first and arXiv fills in underneath, in a separate list appended to rather than
merged, so nothing already on screen moves when the network lands. Device results appear in
about a second; the arXiv half arrives when it arrives.

Three details that turned out to matter more than the split itself:

- **The ordering slider had to appear with the first results, not the arXiv ones.** It was
  conditioned on the arXiv list being non-empty, so it materialised late and shoved the block
  the reader was already reading down the screen. The same class of mistake the two-phase
  digest was removed for.
- **Device hits must not narrate a model that was never consulted.** They are ranked by query
  match alone, deliberately, so that they can appear without waiting for training; reporting
  "outside your usual reading" for a paper the reader saved last week would be inventing a
  judgement out of a score nobody computed. They report what the reader did with the paper
  instead.
- **The block is capped at six.** It is context, not the answer: it says "you already have
  these" so nobody re-reads a paper they judged last month. A long block would push the
  arXiv results, which is what was actually asked for, off the screen.

A side effect worth more than the speed: an arXiv search with no network now returns the
device results and a line explaining the rest, instead of an error.

## Earlier: what the occasional reader missed

The `shown` table keeps a row per day and nothing read yesterday, so missing a day lost those
sixty papers. The obvious fix, a browser over past days, turns out to answer the smaller half
of the question.

**The daily worker fetches papers but never composes a digest.** It calls `upsertPapers` and
stops. So the days somebody was away leave no `shown` rows at all: those days are not in the
table to browse. What is on the device is the papers themselves, hundreds of them, that no
digest ever selected. On this device, 779 from the last seven days.

So there are two different needs and the data says which is which:

**Catching up** is the one that matters, and it is not about `shown`. It is the papers
announced since the last digest the reader was actually shown, minus anything a digest put in
front of them and anything they have touched. Several hundred papers is not a catch-up, it is
a second job, so they are ranked by the same machinery that picks sixty from three hundred
each morning and the best twenty-five are shown. A fortnight away costs one screen.

**Replaying a past digest** answers "where was that paper on Tuesday". Cheap, since the day's
order, slots and reasons are all stored, and shown exactly as it was: re-ranking the record
with today's model would answer a question nobody asked.

Both live on one Earlier screen, reached two ways. A card at the top of the digest, shown
only when there is a real gap of two days or more, so a daily reader never sees it; and a
link on the end card, where "and before today?" is a natural question and it costs no tab.
Phrased as papers rather than days, because "142 papers" is a quantity somebody can decide
about where "you were away 3 days" is a fact about them they already know.

**The model is now warmed in the background.** Reopening the app restores the stored digest
without training, which is why it is fast, and the bill used to arrive at whatever first
needed a model. The catch-up list took seventeen seconds for that reason. Training once the
digest is already on screen took it to five, and does the same for the first search of a
session, which had been an open item since the search rewrite.

## More than one preprint server

"No cell biology?" turned out to be two questions with two different answers.

**The taxonomy was a fraction of arXiv's own**: 37 topics reaching 54 categories where arXiv
has around 150. Cell biology, software engineering, chemistry, information retrieval,
programming languages, nuclear physics and most of pure mathematics were all missing, for no
reason except that the list had been written from one field outward. Now 82 topics reaching
105 categories.

**And the rest is genuinely not on arXiv.** No chemistry archive, no medicine, and a biology
section that is quantitative biology rather than wet-lab work. bioRxiv and medRxiv are now
first-class sources: one public JSON API, no key and no bot check, returning the title and
abstract the ranker needs plus a `published` field that gives the venue signal for free by
the same route arXiv does, months later on a paper that turned out to matter.

**What multi-source costs.** Identifiers, URLs and category names all become per-server, and
every one of those is a place where a bioRxiv DOI can be handed to arXiv. Three real bugs came
out of it, and the shape of each is worth keeping:

- **A filter comparing two different namespaces.** Subjects are stored qualified
  (`biorxiv:cell biology`) so that bioRxiv's "genomics" and arXiv's `q-bio.GN` stay separate
  feeds; the fetch then compared the qualified form against the API's bare subject name and
  matched nothing. The failure was not an error, it was a server appearing to have posted
  nothing that week.
- **Two cursor mappers.** `Db` built a `Paper` column by column in two places, and the new
  `source` column was added to one of them. Every paper in every *list* claimed to be from
  arXiv while the row in the table said bioRxiv: a missing label first, a broken PDF link next.
  There is one mapper now.
- **Text that had quietly become false.** "On arXiv" on a bioRxiv paper, and "recent in your
  categories" on a paper fetched for the bridge slot that was in nobody's categories. The
  second now names the category instead of claiming it.

**The picker had to change shape.** Nine fields and 82 topics in one flat scroll buried biology
and medicine under twenty computer science chips, so the reader who most needed to know they
were there had to scroll past everything they did not want to find out. Collapsed, the first
screen is the map: nine lines saying what this app covers, with a count on each.

Verified end to end on a fresh install as a cell biologist: 95 bioRxiv papers fetched across
cell biology, cancer biology and immunology; a digest of 25 with 23 of them from bioRxiv; the
card reading "Grzes et al. · bioRxiv · cancer biology"; and a 21-page bioRxiv PDF downloading
and rendering in the built-in reader. The v6 to v7 migration was then run against a real
database of 1974 papers and 125 signals with nothing lost.

## Three from using it on a phone

**Scroll position was lost on every list.** Opening a paper replaces the screen rather than
pushing onto a back stack, so the list underneath is disposed and its `rememberLazyListState`
goes with it: scroll halfway down the digest, open the fortieth card, come back, and you are
at the top with no way to find where you were. Every screen is now wrapped in a
`SaveableStateHolder`, which is what a navigation library installs for the same reason. It
fixed For You, Explore, Popular, Library and Search at once, and switching tabs and back now
keeps position too.

Library still drifted by one card after that, and the cause was not the scroll at all:

- The reacted shelf was sorted by label, so sixty "more like this" judgements shared a score
  of 0.95 and their order came down to whatever SQLite returned. It is now ordered by when
  each was judged, most recent first, which is stable and is also what somebody looking for
  what they reacted to last week actually wants. The paper id breaks ties, because an import
  or a migration writes a whole batch with the same timestamp.
- The shelves were rebuilt on any change to any reaction, including the `viewed` flag that
  opening a paper sets and that no shelf displays. Keyed on the saved count and the judged
  count instead. With both fixed, the screen before and after opening a paper is
  pixel-identical.

**A notification switch could be turned on when notifications were impossible.** It wrote the
preference whatever Android thought, so a reader who had declined the permission got a switch
that stayed on and a notification that never came. Enabling either switch now asks for the
permission, which is the right moment to ask: they have just said what they want. If the
answer is no, the setting is not written and the switch does not move. Android stops showing
its own dialog after two refusals, so when the permission is gone for good the section says so
and offers a way through to the system screen, which is otherwise unreachable.

Granting on first run now switches the evening reminder on at 19:00. Somebody who has just
said yes to notifications wants one, and the evening is the useful hour: the digest is built
in the morning and read when there is time. Only on the first grant, so it can never switch
itself back on after being turned off. That took two attempts: the first put a "have we asked"
guard inside the function, and the permission callback marks the prompt as asked before
invoking its continuation, so the guard saw its own flag and the feature did nothing.

**The share line had become false.** It said "an offline arXiv reader", which stopped being
true when bioRxiv and medRxiv arrived. It now names all three and carries the repository, so a
colleague who wants the app can act on it and one who does not has lost a line.

## Doing the waiting before the reader arrives

Four places where the app made somebody wait for work it could have done already.

**Refreshing re-fetched what it had just fetched.** Pull to refresh always went to the
network, however recently it had been. arXiv announces once a weekday, so the second request
returned the same papers: six seconds for arXiv alone, half a minute with bioRxiv and medRxiv
on. The decision is now a pure function with four cases, tested rather than reasoned about:
anything announced means fetch everything; a reader asking after ten minutes means fetch
everything, because bioRxiv has no announcement to key off; a subject ticked since the last
fetch is pulled on its own, since it has no papers here at all; otherwise nothing is asked for
and the screen says "Nothing new announced, re-ranking what you have". Verified by watching
`last_fetch`: it does not move.

That message had to be rescued twice. The line after it overwrote the label with "Ranking"
before anybody could read it, which is how the one sentence that answers "did it even check"
never appeared.

**The nightly worker fetched the papers and stopped.** It downloaded at five in the morning,
on wifi, on a charger, and then left the ranking to be paid for on the first open of the day.
It now composes the digest too, through a `DigestBuilder` that the screen also calls, so there
is one implementation rather than two that drift a weight at a time. The first open of a day
the worker ran is a database read.

**Onboarding left the network idle.** The survey's second pass was twelve more requests, one
per topic, three seconds apart, to fetch papers one at a time. It now pulls the reader's real
feed instead, once, while they are answering questions: the same papers, fewer requests, and
the digest is already downloaded when they finish. First question 6.6 seconds to 2.9, and 303
papers on the device by the end of the survey where there used to be two dozen.

**Explore and Popular were computed when first opened.** Both are now built once the digest is
on screen and the phone is otherwise idle. Tapping Explore the instant the digest landed still
cost fourteen seconds, because the warm-up and Explore were each fitting their own model and
throwing one away; a lock around model fitting made the second caller wait for the first, and
took it to eight. A reader who spends a few seconds on the digest first finds it instant.

**And the app opens on the screen that is ready.** When there is no digest for today, For You
is placeholder cards for several seconds while Popular is a sort over what is already stored.
Landing on Popular there is not a preference about which feed matters; it is refusing to show
a promise of reading when there is real reading to hand. Decided once at launch and never
revisited, because moving somebody to another tab when a background job finishes would be
worse than the wait it saves.

## The app's own colours and letterforms

Feasible as asked, and it fixed a crash on the way.

**The app was calling `dynamicLightColorScheme` unconditionally with a minimum of Android 8.**
Those APIs arrived in Android 12. Lint had been reporting it as an error the whole time and
nothing was reading lint, so every device running Android 8 to 11 would have crashed at
launch. Guarding dynamic colour behind the version check, which is required anyway once it
becomes optional, removes it.

**Colour.** Material You derives every surface from the wallpaper, which is a good default
for a launcher and a poor one for a reader: the ground under a thousand words of abstract
should not change because somebody swapped their wallpaper. The app now ships warm paper and
green ink, taken from its own icon so the two are recognisably the same thing, with the
reading ground lighter than the icon's cream because warmth that is pleasant at icon size is
tiring at full-screen size. Dark is ink on warm charcoal rather than paper: a dark theme that
tries to be paper comes out grey. Every foreground and background pair was checked against
WCAG before it was written; all clear AA and most clear AAA.

Wallpaper colours remain one switch away, and below Android 12 that switch is shown disabled
rather than hidden, since its absence would look like a bug rather than a platform limit.

**Type.** Papers are set in a serif and now so is this. The system serif is used rather than a
bundled face: no download, no licence, no APK, and on Android it is Noto Serif. The whole type
scale is mapped in one place so nothing is left behind in the default sans.

**A consequence worth recording.** The app draws edge to edge, so the system's status bar sits
on the app's own background, and nothing had ever told it which way to go. Against the new
light palette the clock and battery came out white on cream. The theme now sets it.

## Settings as an index

Nine sections in one column, with eighty subject chips in the middle of them, meant that
finding the reminder hour involved scrolling past every field of science. It is now seven
pages behind an index that fits on one screen: subjects, appearance, ranking, notifications,
your library, the model, about. One level, one piece of state, and the back gesture leaves the
page before it leaves settings.

The subject picker was also the last place still listing all eighty topics flat. It and
onboarding now share one collapsible component rather than two copies of which only one had
ever been fixed.

## Three corrections to the theme

**The dark accent was the one cold thing on the screen.** It was a 54% saturated mint, which
against warm charcoal read as brighter than anything it labelled and belonged to a different
palette from the ground under it. It is now a warm moss: the same green family as the light
theme's ink, half the saturation, turned towards olive. Still 8.4:1 against the background,
so nothing was traded for the calm.

**Serif for the paper, sans for the app.** Setting everything in a serif was coherent but
lost a distinction worth keeping: a journal sets its article in a serif and its running heads
in something else, and that split marks where the app stops talking and the paper starts.
Titles and abstracts now take their face from a composition local; everything else takes the
theme's. Two switches rather than one, defaulting to serif papers in a sans app, because
either half is a taste somebody might have.

**A heart and a star mean the same thing.** Both are marks of approval, and neither says
which one comes back later, so a reader had to remember which of the two was the bookmark. A
bookmark says only "later" and says nothing about liking, which is exactly the split the app
already makes between steering the model and keeping a paper. Drawn rather than imported:
material-icons-core has no bookmark and the extended set is several thousand icons to gain
one, which is a poor trade for an app that ships on F-Droid. The X and the heart stay.

Refactoring the card actions to take either an imported icon or a drawn one dropped the click
handler from the imported branch, which would have left the X and the heart inert. Caught by
tapping them and looking at the ledger rather than by reading the diff.

## Deleting a download

The Offline shelf listed downloaded PDFs and offered nothing to do with them, which made it
the only dead end in the library. The papers are three to thirty megabytes each, so a reader
who uses the app for a year has a real question about storage and no way to ask it.

**It belongs on that shelf and nowhere else.** A reader wondering what the app is costing
them is asking about exactly the files that shelf lists, so the answer goes where the files
are rather than in a settings page they would have to think to look in. The detail screen was
the other candidate and was rejected: it is a screen for reading, and it would gain a button
that is pressed once for every hundred times it is not.

Four parts, each answering a question the reader actually has:

- **A total at the top**, "8 papers · 74 MB", because "what is this costing me" is the
  question that brings somebody here.
- **A size on each row**, because deciding what to delete needs to know what each one weighs.
- **A delete on each row**, matching the affordance the Saved shelf already uses.
- **Delete all**, because reclaiming space one paper at a time is not a feature. Confirmed in
  place, since these files are unreplaceable until there is signal again, which is the exact
  situation they were kept for.

**Deleting a download is not changing your mind.** The file goes and the save and the ledger
stay, and the confirmation says so in as many words. That invariant is now a test rather than
something to be careful about, because it is the one way this feature could quietly do harm.

The store stays in the cache directory. That was a deliberate choice, recorded where it was
made: the system can reclaim the space under pressure and the reader is never asked for a
storage permission. What was missing was a way to reclaim it earlier than the system would.

## A download that did not appear on the offline shelf

Reported: downloading a paper opened from the library did not put it on the offline shelf,
while downloading the same paper from the digest did.

The shelf is rebuilt by an effect keyed on the tab and on two counts, and a finished download
changes none of them. Coming from the digest works by accident: that route leaves the library
tab and returns to it, and the tab changing is what rebuilds the shelf. Reaching the reader
from inside the library never changes tab, so nothing rebuilt it.

The key was narrowed deliberately, to stop the library reshuffling when a paper was merely
opened, and this is what that narrowing cost. The fix is the one the delete already used: the
download puts itself on the shelf the moment the file lands, rather than waiting for
something else to notice. The shelf's contents also no longer depend on the paper having a
reaction row, which was true of every download by accident rather than by design.

**Two more found while going through the app afterwards.**

The offline shelf came back in whatever order SQLite returned. On a screen whose job is
reclaiming space the useful order is heaviest first, which is now what it does.

The detail screen's back button said "Back to digest" from all six places that open it: the
digest, Explore, Popular, the library, a search and an earlier digest. It says "Back".

**And one thing that was not a bug.** A device search for "protein folding" returned a graph
neural network paper under "already on your device", because the local search matches
substrings and the abstract contains "unfolding". Recall matters more than precision for
"where did I put that paper", and it was the only local match there was, so the ranking had
nothing better to put first. Left alone.

## One menu per row, built from the paper

The three library shelves had three different trailing controls: a bin meaning "unsave", a bin
meaning "delete the file", and a pair of chips. Three affordances for the same kind of row, and
one of them a bin that meant two different things depending on which chip was selected.

**The menu is built from what is true of the paper, not from which shelf it is on.** That was
the one departure from the request, which described a menu per shelf. A saved paper may also
be downloaded and may also have been reacted to, so a per-shelf menu has to either leave out
real options or offer ones that do not apply. Asking the paper gives one implementation that
is correct on all three shelves and stays correct if a fourth appears:

- Save for later, or remove from saved
- More like this, less like this, and clear my reaction
- Download for offline, delete download, or "downloading…" while one is in flight
- Share

**Steering stayed out of it.** The first version put more, less and clear in the menu too,
which made a management menu the fourth place to judge a paper and the only one doing it
without the paper in front of you. The reacted shelf keeps its two chips: that shelf exists
to change your mind, so the control for it is the row's own affordance rather than something
behind a tap, and it shows which way you went as well as moving in one press. The menu is
three items, and none of them is an opinion.

**What it fixes beyond tidiness.**

Downloading was only possible by opening a paper and waiting for the reader to render it,
which is a poor way to prepare for a flight. From a row a reader can line several up and leave
them to it. No dwell timer is armed for these, unlike the reader's: fetching a file is not
reading it, and the download signal is worth 0.7 precisely because it means somebody stayed.

The share sheet's text was written inline at the detail screen's call site, and is now one
function: a share that said something different depending on which screen you started from
would be a small mystery nobody needs.

## The survey was asking thirteen times for one request's worth of papers

**It was not fetching per answer**, which was the suspicion, but the effect was worse than
that. It asked arXiv for one paper per topic, twelve separate times, three seconds apart,
because the published rate limit is one request every three seconds. Thirty-six seconds of
asking to obtain twelve papers, and the deck therefore filled more slowly than anybody
answers: a reader who was quick ran out of cards and waited for the next one to arrive.

The papers were already available, and the guess about the digest was exactly right.
`recent` returns three hundred across every subscribed category in **one request** — the same
pull the digest makes a moment later. Measured against arXiv directly, that request is 727 KB
and 1.3 seconds. The survey now draws its deck from it, bucketed by topic and taken
round-robin so the questions still alternate subject.

**One request per server replaces thirteen**, and the same download then serves the digest,
Explore and Popular without being asked for twice. The keyword-probe endpoint and the search
phrase each probe carried are gone, along with the code that stitched two passes together.

**Fetching while the reader chooses.** Picking subjects takes ten or twenty seconds of
expanding fields and reading names, and the app spent every one of them idle before making
the reader wait for a fetch it could have finished already. The feed is now pulled as soon as
the selection holds still for a second, debounced so that ticking four subjects makes one
request rather than four. The survey joins that job rather than starting its own, and asks
only for subjects it did not cover.

Measured end to end on a wiped install: first question 2.3 seconds after asking for papers,
the full deck of twelve available at once with no stalling across eleven rapid answers, and
finishing the survey performs **no network request at all** — `last_fetch` does not move,
because the papers arrived while the reader was still choosing.

## Onboarding as three promises

The first screen was a wall: what the app is, what the survey will ask, how long it takes, a
privacy note and a BibTeX importer, all before anything had been shown.

It is three swipeable pages now, one sentence each, saying the three things that are actually
unusual about this app: the ranking is a model that lives on the phone, nothing about the
reading goes anywhere, and papers can be read and kept rather than thrown at a browser.
Skippable from the first frame. It is deliberately not a feature tour — somebody still
deciding whether to spend a minute on the survey needs three reasons, not thirty.

The importer moved to the subject screen, which is where it belongs: importing a library and
answering the survey are the same question asked two ways, and it was a technical aside in
the middle of a promise.

## The "why" chip was explaining the wrong thing

A digest captioned five cards "matches status, train", "matches best, independent, training",
"matches optimal, thereby, known", "matches learning, principal, where". Half those words name
no subject, and a chip that claims a match it cannot support is worse than no chip.

The chip printed the classifier's top-weighted features for the document. That is the wrong
quantity. The model is fitted on a few dozen positives against sampled negatives over a
vocabulary of thousands, so the weights are badly underdetermined: a word appearing in two
liked abstracts and no sampled negative earns a large positive weight whatever it means. When
a paper genuinely matches, real topic words outweigh the noise and the chip reads well, which
is why this was invisible for a reader with a coherent library. When it does not match, there
is nothing real to report and the chip prints the noise.

Three obvious repairs were tried against 32,584 arXiv abstracts and all three failed.

- **Filter by how common a word is.** The offenders are rare. "status" occurs in 0.3% of
  abstracts and "principal" in 0.7%, against 6.1% for "diffusion" and 4.9% for "transformer".
  No frequency threshold separates them.
- **Prefer two-word terms.** Abstract boilerplate is mostly two-word. It produced "state art",
  "results demonstrate", "end end".
- **Require a term to recur across the reader's liked papers.** Worst of the three. A reader
  with varied taste has only generic language in common, so it selects for "role", "many",
  "finally" and drops "scene graph" and "point cloud".

What works is asking a different question. Take the paper the reader kept that this one most
resembles, and name the words that make the two alike. Those words are the overlap of two
specific documents, so they describe a subject rather than an artefact of the fit, and the
same measurement says how strong the resemblance is. It turned "transition, adding, mutation"
into "species, mutations, populations" and "i2p, autoencoder, one one" into "sparse,
autoencoder, diffusion".

Only papers scoring 0.6 or better count as references, which is saved, liked, downloaded,
shared or actually read. A paper merely opened scores 0.25, and citing it as a reason would be
the caption overstating its evidence again.

Below 0.06 overlap a card says nothing rather than guessing, and falls back to naming its
category. The floor is calibrated against the distribution over 600 random candidates, where
the lower quartile is 0.062, while cards that reach the digest score 0.12 to 0.20. Across five
digests built from a real 38 paper library, 0 of 25 cards per digest were silenced by it and
the lowest scoring card shown was 0.088.

The filler list grew and its rule changed. It used to drop any term containing a filler word,
which cost the phrases worth keeping: "optimal transport", "state space", "image quality". A
term is now dropped only when every word in it is filler, which still discards the boilerplate
that is filler end to end.

`LogReg.topContributors` is gone. Nothing explains itself from the weights any more, and the
reason is computed once for the cards that reach the digest rather than for every candidate
that was going to be thrown away.

### What the rebuilt digest then showed

Reading real chips off the device found four more faults, each its own kind.

**Function words were features.** A card read "matches cluster, defined, them" and another
"matches gap, constant, there". "them" and "there" are not vocabulary, they are grammar, and
leaving them in gave the classifier something to overfit. They are now in the vectoriser's
stoplist along with the rest of the closed class, which is a change to the model and was
measured as one: leave-one-out over a real 38 paper library scored an identical hit@10 on four
seeds, one of which improved from 36 to 37 of 38. Hyphen fragments went the same way, since
splitting "non-linear" had been putting a bare "non" on a card.

**"not" stayed in, and is filtered from captions instead.** The vectoriser's list is short
because negation carries method meaning, and that is still right. A word can belong in the
model and not in a caption, which is the reason the two lists are separate.

**A word stood in for the phrase it came from.** A chip read "datasets, language, shot",
because the vectoriser scores "shot" and "few_shot" separately, the bare word often wins, and
the phrase was then discarded as a repeat of a word already shown. Where the list holds a
phrase containing the word, the phrase is now shown in its place.

**Singular and plural were printed as two reasons.** "matches layers, layer, update", and
"trajectory, trajectories, call". They are one word to a reader and the card gives the
explanation one line. Compared pairwise rather than stemmed: stemming has to guess at a word
on its own, and would reduce "bias" to "bia" while reducing "biases" to "bias", leaving both
on the card.

A contribution threshold was tried for the weak third term and rejected. The decay across
shared terms is too gentle to separate anything: measured over the top 25 cards of a real
digest, the second term holds 77% of the first's weight and the third 59%, so any cutoff that
removes a weak third term removes as many good ones.

## Subjects nobody could choose

An audit against what the three servers actually publish found categories no topic listed. A
category no topic lists is never fetched, so for the reader those papers do not exist and
nothing says so.

**arXiv: 46 of 155 categories were unreachable.** Four of the 50 first flagged turned out to
be alias pairs whose canonical form was already mapped, which a zero result for
`cat:math.MP` against 666 for `cat:math-ph` makes obvious. The rest were real, and the
largest were not small: math-ph 666 submissions in August 2026, cond-mat.mes-hall 557,
astro-ph.IM 456, astro-ph.SR 440, cond-mat.stat-mech 431, astro-ph.EP 315, physics.comp-ph
290, cs.CE 207, physics.app-ph 200, cond-mat.supr-con 184. Astrophysics had three of its six
subfields and was missing the larger half. Most were added to the topic they belong to; four
areas with no home at all got topics of their own: mathematical physics, applied and
computational physics, instrumentation and detectors, and physics, society and history.

Deliberately still unmapped are the "General", "Other" and "Popular" catch-alls, which exist
to hold what the moderators could not place and are not a subject anyone follows.

**medRxiv: five subjects unreachable** (pathology, primary care research, nutrition,
pharmacology and therapeutics, palliative medicine) and **bioRxiv one** (paleontology, which
published nothing in the whole June to August sample, which is exactly why it stayed
invisible).

**Physiotherapy existed but could not be followed.** "rehabilitation medicine and physical
therapy" was one of eighteen subjects inside a single "Clinical specialties" topic whose seed
vocabulary was surgical: "patients procedure postoperative outcomes". A physiotherapist could
reach their own field only by subscribing to seventeen other specialties and being ranked
against operative language. That bucket is now five topics: rehabilitation and physiotherapy,
surgery and perioperative care, women's and children's health, emergency and intensive and
palliative care, and what is left. Checked on the device: choosing it alone fetches five
medRxiv papers on exoskeletons in physiotherapy, rehabilitation wearables and exercise
interventions.

`TopicCoverageTest` holds each server's subject list and fails when one is unreachable, with
an explicit list of the exclusions and why. Writing it immediately caught three faults in the
change it was meant to guard: paleontology missing, "pain medicine" listed under two topics
at once, and one assertion comparing a list to a set.

### Fields with no source at all

Chemistry, law, psychology, social science and education are not on arXiv, bioRxiv or
medRxiv, so no amount of taxonomy work reaches them. Two APIs would.

- **OSF**, one API across 32 preprint servers. Live ones measured by publications since
  2026-08-01: PsyArXiv 1702, SocArXiv 848, EdArXiv 233, Law Archive 55, MetaArXiv 41. Several
  others are dormant, having moved to their own platforms: SportRxiv last published in 2021,
  engrXiv 2022, EarthArXiv 2020, AgriXiv 2020. PsyArXiv alone is larger than most fields the
  app already carries.
- **ChemRxiv via Crossref.** ChemRxiv's own API sits behind a Cloudflare challenge and
  returns 403 to a plain client, which an Android app cannot honestly work around. Its DOIs
  are registered under prefix 10.26434 and Crossref serves them openly with abstracts: 547
  posted in the first twelve days of September 2026. Crossref carries no subject field for
  them, so chemistry would arrive as one pool and the ranker would have to separate it, which
  is what the seed vocabularies already do.

## Five fields that had no source

Chemistry, psychology, social science, education and law are not on arXiv, bioRxiv or
medRxiv. No amount of taxonomy work reaches them, so two APIs were added.

**OSF Preprints** serves thirty-two preprint servers through one endpoint, which is the whole
reason it is worth doing: four fields for one integration. Only the live ones are wired up.
Measured by publications since 2026-08-01: PsyArXiv 1702, SocArXiv 848, EdArXiv 233, Law
Archive 55. SportRxiv last published in 2021, engrXiv in 2022, EarthArXiv and AgriXiv in
2020; pointing the app at those would be offering a subject that never updates. Records carry
a hierarchical subject taxonomy, so a topic subscribes to the leaf, "Cognitive Psychology" or
"Criminal Law", exactly as it does to a bioRxiv subject.

**ChemRxiv through Crossref.** Its own API answers a plain client with a Cloudflare challenge
and a 403. Crossref is the route that is meant to be used: every ChemRxiv preprint is
registered under DOI prefix 10.26434 and Crossref serves the records openly, with abstracts.
Crossref carries no subject for them, so chemistry arrives as one pool and the five chemistry
topics share one category; their seed vocabularies are what separate organic from analytical,
which is the same mechanism that already orders a digest. Abstracts arrive as JATS XML and
are stripped, because markup left in becomes a model feature, as "matches textbf, reasoning,
tasks" demonstrated once already.

### Three things measurement changed

**Nobody waits on a server they did not ask for.** Seven servers now, and no reader wants all
seven. `Fetcher` asks a server only when the reader's own categories name one of its subjects,
which falls out of `Topics.categoriesOf` returning an empty set for the rest. `FetcherTest`
pins it: a computer scientist wakes arXiv alone, a lawyer wakes the Law Archive alone.

**OSF costs payload, not requests.** Fifty records take 25s, a hundred take 55s, and asking
for only the six fields the app reads halves it to 13s. Embedding contributors, which is the
only way to get author names, costs more than everything else combined: 13s for ten records,
27s for twenty-five, and a timeout past sixty for fifty. Authors were dropped. Nothing in the
ranking reads an author and the byline already omits a missing one, so an OSF card reads
"PsyArXiv · perception" where an arXiv card reads "Park et al. · cs.LG".

**One window does not fit servers two orders of magnitude apart.** PsyArXiv posts around forty
a working day and the Law Archive around nine a week. The three day window that serves the
others returned 35 psychology papers and zero law papers, which on screen is indistinguishable
from a server with nothing to say. OSF uses fourteen days. Results come back newest first and
the page cap bounds the volume, so the wider window costs the busy servers nothing.

ChemRxiv PDFs cannot be fetched at all: the DOI resolves to chemrxiv.org, which is the same
Cloudflare wall. Those papers say "Read on ChemRxiv" and open a browser, which can pass the
challenge, rather than offering a download button that always fails.

## A hundred and fourteen topics needs a search box

Fourteen fields is past the point where scrolling finds your own subject, and the reader who
most needs the list is the one who does not know which field the app filed them under.

The search reads topic labels, field labels, the archive codes and the seed vocabularies, so
the word typed does not have to be one of the app's names for anything: "superconductivity"
finds condensed matter, "qubit" finds quantum physics, "galaxy" finds astrophysics. Matching
allows a six letter prefix, because the seeds say "superconducting" and nobody types that.

That prefix rule immediately created its own bug. "physiotherapy" and "physiology" agree for
six letters, and ranked together the physiology topics came first, above the topic actually
called physiotherapy. A literal match now outranks a prefix match, and `TopicSearchTest` holds
that case along with the words readers actually type.

## What a field with four papers a day exposed

Everything here was built against arXiv subjects that publish hundreds a day, where a pool is
always larger than a screen. Following law alone breaks that assumption in five places, and
four of them were real faults rather than wording.

**The digest filled itself with papers nobody asked for.** The candidate pool is everything
recently fetched, and the bridge fetches outside the reader's fields on purpose, so those
papers sit in the same table as the rest. Every slot drew from all of it. For a broad reader
that was invisible: eighty outside papers among a thousand subscribed ones rank low and rarely
surface. For a law reader it was the entire morning, twenty five cards of cs.CY, cs.AI and
q-fin, and not one of them law. Papers from unsubscribed categories are now candidates for the
one bridge slot and nothing else, and a short day stays short: four papers the reader chose
beat twenty five they did not.

**The survey ate the day's supply.** It draws from the same papers the first digest will, and
it took all twelve it was sized for. Two law topics produce about six papers a fortnight, so
the survey asked about all six, every one became finished business, and "Show me today" opened
on an empty digest. It now leaves five behind when there are that few, never going below the
three keepers that switch ranking on. A field posting more than a handful a day is unaffected.

**A server that failed was reported as a quiet day.** Every API returned an empty list for both
"nothing new" and "did not answer", so an OSF timeout reached the reader as "Nothing new
today. arXiv does not announce at weekends or on US holidays" — on a profile with no arXiv in
it, about a server that had simply not replied. A first page that fails now throws, the fetcher
records which servers failed, and the screen says "Could not check" and names them. When the
fetch did work, the message names the reader's own servers rather than arXiv's calendar.

**Popular is empty for most of the world, permanently.** It ranks by upvotes on the Hugging
Face daily list and by conference acceptances. Both cover arXiv, the daily list leans heavily
to machine learning, and OSF and ChemRxiv publish neither. "This fills in once a digest has
been fetched" was false in a way the reader could disprove by waiting. The three cases are now
distinguished, and the one that will never fill says so. It does not fall back to showing the
list itself: filling a lawyer's screen with the day's most upvoted machine learning papers is
the thing this app exists to stop doing.

**Explore offered a button that could not work.** It shows what did not fit in the digest, and
in a narrow field the digest takes everything, so it rendered its "deliberately less sure of
itself" header over nothing above a More papers button that returned the same nothing however
often it was pressed. A short page now ends with a sentence instead of a button, the empty case
explains that nothing is being withheld, and a tab known to be empty is no longer re-ranked
against eight hundred candidates on every visit.

Both empty screens offer the one setting that changes the answer, and a deep link into Subjects
returns to where it came from rather than to a settings index the reader never opened.

OSF's unreliability is worth recording: across one afternoon the same request returned a 200 in
7s, a 200 in 27s, and a 500. Its read timeout is 60s because a 30s one turned the slow half of
that range into silent emptiness. The live tests skip when a server does not answer, so they
check our parsing rather than somebody else's uptime.

## Not every preprint is a PDF

A Law Archive paper downloaded, and the reader showed "Fetching the PDF" and never stopped.
The file was a .docx. Preprint servers hand over whatever the author uploaded, and OSF serves
Word documents often enough that this is ordinary rather than exceptional.

Everything downloaded was named `.pdf`, because that is what the app called every download.
PdfRenderer opened the .docx, failed, and reported no pages, which is indistinguishable from a
document still arriving. So the spinner was correct about what it knew and wrong about
everything else.

Downloads are now identified by their bytes rather than by what was asked for: a PDF starts
`%PDF-`, the Office formats are zip containers starting `PK`, and the server's own
Content-Disposition filename supplies the extension. The cache keeps the real extension, which
is also what decides the media type when the file is handed to another app.

A file the reader cannot draw now says so and offers it to an app that can, through a
FileProvider, since a `file://` URI cannot be passed to another app at all. It goes out via a
chooser rather than a direct launch, because on a phone with nothing installed for the format
`startActivity` throws and the button looks broken. Once the file is on the device its type is
known, so the button says "Open with another app" rather than "Read" and the explanation
screen is never reached.

Nothing else changes for those papers: they are downloaded, they are on the offline shelf, they
rank the same. Only the rendering is beyond us.

## Three details on the abstract screen

**"Sh-are".** The three actions sat in a fixed `Row`, and their widths depend on the source
name and on the reader's font scale. On a narrower screen the last was squeezed until its label
broke across two lines. They are in a `FlowRow` now, so a button that will not fit moves to the
next line, and no label may break inside a word.

**The judgements moved to the header as well.** They already sat on every card and at the foot
of this screen, but this is where the reader is when they have actually read the abstract and
formed the opinion, and an abstract can be long enough that the foot is several scrolls away.
The same three icons in the same order as the cards, so there is nothing new to learn.

**The title opens the paper.** It is the most obvious thing on the screen to press and it did
nothing. It does exactly what the button at the foot does, decided in one place so the two
cannot drift apart.

## Swiping between the four screens

The bottom bar was the only way across, which on a phone is the one navigation people do not
use: every other feed on the device is swiped. The four screens are a `HorizontalPager` now,
in the order the bar already showed them.

The pager is the state rather than a second copy of it. The bar reads the current page and
tapping it animates the pager there, so a tap and a swipe arrive the same way and the two
cannot hold different ideas of where the reader is. The page being drawn is not the page
selected while a swipe is in flight, so each page renders from its own index; reading the
selected tab inside the pager would draw the same screen on all four and the swipe would look
like the content sliding onto itself.

`currentPage` rather than `settledPage` decides the title and any loading a screen needs, so
those start once a swipe is more than half way and the page has something on it by the time it
arrives. Each page keeps its own scroll through the `SaveableStateHolder` that was already
there for opening a paper, so swiping away from a half-read list and back returns to where it
was rather than to the top.

Checked on the device: all four transitions in both directions, stopping at the ends; the bar
and the title following a swipe; a scrolled Explore still scrolled after swiping to Popular and
back; each tab's content still loading when swiped to rather than tapped; and a paper opened
from a swiped-to tab returning to that tab.

There are no horizontally scrolling surfaces inside any of the four, which is the usual thing
that fights a pager, so nothing needed a nested-scroll arrangement.

## What the reader can do with a downloaded PDF

An overflow menu rather than a row of icons. The bar already carries a back button, the paper's
title and the zoom control, and the title is the part that suffers: one line, ellipsised before
anything is added to it. Three more icons would leave it showing about two words. It is also
the shape the library rows already use for the same problem.

Three items, chosen because they are things the app could not otherwise do.

- **Share this PDF** sends the file. This is a different act from the Share on the abstract
  screen, which sends a title and a link, and neither substitutes for the other when the person
  receiving it is standing next to you with no signal.
- **Open with another app** is how a reader gets annotation and text selection, which this
  renderer deliberately does not have.
- **On <source>** goes to the paper's own page. That is the only honest reading of "open
  location": the file itself lives in the app's private cache, where no file manager on the
  device can reach it, so there is no location to open.

The shared file is a copy named after the paper. The cache names files by paper id, which is
right for the cache and wrong for a recipient: the first share arrived as
"lawarchive:4vpd7_v1.pdf", and the colon in it is not a legal filename character on Windows or
on a FAT card, so saving the attachment fails rather than merely looking odd. The share
directory holds one file at a time rather than growing a copy per share.

The menu opens under its own button. Emitted as a sibling of the reader bar's other children
it anchored to the row rather than to the control, and dropped down against the far left of the
screen; both now sit in one Box.

## Onboarding headlines that say the thing

The three headlines were written as promises, which reads well and leaves the reader to work
out what was meant. "The day's research, without the rest of it" cannot be resolved until the
body has been read, and "Read it here, and keep it" leans on two pronouns with nothing yet to
refer back to. A headline has about a second to land, for somebody who does not yet know what
the app is.

Each one now names what it is talking about: hundreds of papers a day and a personalised
selection, learning your interests on your device, reading here and saving offline. The bodies
were already plain and mostly stand. Where a headline now carries a fact the body no longer
repeats it: the first body used to open by counting the papers a second time.

The reader's menu then gained the card's three judgements and a link share, in three groups:
judge, share, leave. The judgements come first because the reader is where an opinion about
a paper actually forms, and going back to the card to find the heart means losing the page.
Each shows whether it is already on with the card's icon in the primary colour and a check at
the end of the row; the card gets by with the tint alone because it shows all three side by
side, and a menu row stands on its own. Choosing one that is on turns it off, and choosing Less
on a paper marked More replaces the judgement rather than recording both.

Both shares now record that the paper was shared, as the abstract screen's Share always has.
The PDF share added in the previous round did not, so passing a file on counted for less than
passing on a link, which is the wrong way round.

## Why "outside your usual" kept coming first

It was first on three days in seven on a real phone, with a median position of 1, and it was
never because it was the best match. Reconstructing a day's scores showed why: every card's
relevance sat between 0.31 and 0.34, and a conference acceptance multiplies the score by up
to 1.35, so when relevance is that flat the venue bonus decides the order. The bridge card
that day had an acceptance; the matches did not.

The same flatness broke exploration. It took the papers nearest a relevance of 0.5, which is
where a calibrated classifier is least sure, but relevance is shrunk towards 0.3 until
evidence builds up, so nothing came near 0.5 and "nearest" meant "highest". Five of the top
seven cards that day were labelled "testing whether this is for you", and they were the best
papers in the digest.

Two fixes to how a digest is put together, whatever the scores look like. The top three cards
are always the best matches, and the detours follow, one after every three matches, with the
bridge first among them. Exploration now takes the near misses, a band of papers ranked just
below the ones chosen to show, which is where the digest actually decides between showing a
paper and not, and which cannot reach the top. Rebuilt on the same phone, the digest reads
R R R B R R R e R R R e and so on.

Neither fix makes relevance less flat. The model's raw predictions over that day's 400
candidates ran from 0.26 to 0.40, and only 43% of a new paper's words were in its
vocabulary at all, because the vocabulary is fitted on the 242 papers it trains on. That is
the question E2 is for.

## Hardening

A review of every input the app does not control found these.

- **SQLite's parameter limit.** Android 8 to 11 cap a statement at 999 bound parameters. The
  digest builder looks up every paper carrying any signal, and opening a paper is a signal,
  so a reader opening a few a day passes 999 in about half a year, after which no digest
  could be built on those phones. Lookups are now batched, and search keeps its first twelve
  words, since each costs three parameters.
- **Backups are checked.** Restore accepted any categories and any settings. Categories are
  spliced into the arXiv request, so one reading `cs.LG&max_results=100000` would have added
  a parameter to it; a negative digest size made every rebuild throw. Categories are now kept
  only if a subject offers them, settings are held to the ranges the settings screen allows,
  and absurd ids are skipped. The arXiv client also refuses anything not shaped like a
  category code, as a last line for any future path that forgets.
- **Server filenames.** A download's extension came from the server's filename, and whatever
  follows its last dot could carry a path separator into the cache. It could not climb out,
  having no dots left, but it is now letters and digits or nothing.
- **Download size.** Unbounded, so one broken or hostile response could fill the phone's
  storage. Now refused over 100 MB, including when the server understates the size.
- **Cleartext.** Every request was already HTTPS, but Android 8 permits cleartext by default,
  so nothing enforced it there. The manifest now refuses it on every version.
- **A damaged PDF.** The reader's page count started at zero, and a PDF that would not open
  also reported zero pages, so it showed the loading spinner for good. It now says the file
  looks damaged and offers to download it again or open it elsewhere.
- **No browser.** Opening a paper's page launched the browser directly, which throws when
  there is none, and took the app down. Every web link now goes through one guarded call.
- **Restore was quadratic.** It re-read the whole reactions table for every saved paper.

Checked and found sound: XML is parsed by Android's pull parser, which resolves no external
entities; every SQL statement binds its values; nothing is logged.

## The digest was throwing away most of what the model knew

The two fixes above changed the order of a digest. The next question was whether the right
papers were in it at all, and it needed a way to measure that which does not depend on one
phone and one reader.

### Measuring the digest rather than the model

E1 and E2 score a ranking once. A digest is built every morning from a sample, a topic
bandit, a diversity pass and reserved slots, and the reader's reactions feed the next one,
so a ranking that scores well can still produce a poor morning. The simulation in
`DigestSimulation` runs the app's own ranker, not a copy, for forty mornings.

- **Readers.** Six, built by `prototype/sim_prep.py` from 27,470 public arXiv abstracts:
  broad machine learning (cs.CV, cs.CL, cs.LG, cs.AI, three interests), robotics, numerical
  analysis, security, neuroscience, and one reader of both cs.LG and q-bio.NC with an
  interest in each. An interest is a point in a sentence-embedding space (bge-small,
  deliberately not the app's representation), and how much a reader would like a paper is
  its similarity to their nearest interest. The app never sees this.
- **Behaviour.** A paper in the top 3% of the reader's fields is liked half the time and
  read for a while another 30%. One in the top 10% is opened a quarter of the time. One in
  the bottom half is disliked one time in twenty five. Attention fades a little down the list.
- **Measures.** Great papers delivered: cards in the reader's top 3%, as a share of the most
  any digest could have held that morning. Matches: the average percentile of the ordinary
  cards among that morning's candidates, by the hidden interest, where 50 is a random pick.

### What it found

Eight runs per reader, weeks two to six:

| Reader | Great papers delivered | Matches, percentile | First three cards |
|---|---|---|---|
| Broad machine learning | 0.19 → 0.25 | 42 → 69 | 73 → 79 |
| Robotics | 0.34 → 0.61 | 48 → 72 | 58 → 81 |
| Numerical analysis | 0.18 → 0.43 | 33 → 64 | 38 → 68 |
| Security | 0.43 → 0.65 | 48 → 59 | 41 → 63 |
| Machine learning and neuroscience | 0.21 → 0.38 | 38 → 70 | 63 → 74 |

The first week improves too, most for the two-field reader (0.07 to 0.34). The neuroscience
reader is left out: that field is small enough that every paper is shown either way.

For all five readers, the committed digest's ordinary cards were less interesting on
average than a random pick of the same morning's papers. The model was not the problem. Its
own top 25 for the broad reader sat at the 78th percentile and held 44% of the great papers
available, while the digest built from it delivered 19%. One detail explains why the numbers
were not worse still: the old exploration rule was taking the papers the model rated
highest, which was the main route by which they reached the reader at all. With only the
near-miss change above, the broad reader's figure fell to about 0.10 before the fixes below.

### Six causes, each fixed and each with a test that fails on the old code

1. **The sampler read scores as probabilities.** It drew in proportion to them, which only
   works when they spread over most of 0..1. On the phone they sat within 0.07 of each other,
   and of the model's own top 25, two reached the digest. Scores are now measured in
   standard deviations among the day's candidates before the draw, so the temperature means
   the same whatever the model's confidence.
2. **Relevance was too flat to combine with anything.** Venue multiplies the score by up to
   1.35 and freshness adds up to 0.25, fixed amounts sized for a relevance that spans 0..1.
   Against a spread of 0.07 they decided the order. Relevance now enters the score as its
   standing among the day's papers in the reader's fields, which spans 0..1 whatever the
   evidence. The card still shows the shrunk probability as its confidence.
3. **Topics were primary categories.** Every category a paper is cross-listed from became a
   bandit arm, most of them untried, and an untried arm starts from a flat prior with a mean
   of one half. Readers act on about one card in ten, so against twenty untried arms a topic
   they read won none of 200,000 draws. Topics are now the reader's own categories: a paper
   counts towards the first one it is listed in, and the stored history is kept under the
   same names.
4. **Variety did nothing whenever the bandit ran.** The bandit fills slots one paper at a
   time, and each call started with an empty list of what had been chosen, so there was
   nothing to be different from. The history now carries over. Similarity is priced in
   standard deviations of score too; subtracting a raw cosine from a raw score let
   similarity decide nearly every pick whenever relevance was flat.
5. **The model could see less than half of each new paper.** Its vocabulary came from the
   papers it trains on, 4,580 terms from 242 papers on the phone, and 43% of a new paper's
   words were in it. It now includes the day's candidates, which carry no label, so the
   model sees more without being taught anything different. E2 below measures the gain.
6. **A day smaller than the digest skipped the scope rule.** The ranker returned everything
   unchanged when the pool was smaller than the digest, so the bridge's papers came through
   as ordinary matches. A law reader with one unread law paper and five fetched for the
   bridge saw six matches.

### Tried and not kept

- **A bandit prior at the reader's own engagement rate.** It helped while topics were
  primary categories. With the reader's own categories as topics the flat prior did as well
  or better, and it is the simpler of the two.
- **No bandit at all.** The same for one-field readers, where there is nothing to allocate,
  and for the broad reader. The two-field reader got 0.11 in the first week without it
  against 0.33 with it. That reader is the one the bandit exists for, so it stays.
- **A sharper draw.** Temperature 0.2 did no better than 0.35.
- **Other similarity prices.** Half and double the chosen value were within noise.

### What the simulation cannot say

Venue and freshness have no value to a simulated reader, so it cannot say how much they
should weigh. They keep their designed weights. The readers behave simply, and two sets of
three runs of essentially the same design gave robotics 0.58 and 0.72, so differences
smaller than about 0.1 for the narrow readers are noise. It measures what the digest does
with a model, not how good the model is. That is E2.

## E2: would a sentence embedding rank better?

A real 38-paper library stood in for a reader, against 1,500 papers from cs.CV and cs.LG,
with 3, 5, 10 or 20 of its papers given as liked and the rest to be found, over 25 random
splits. Four small embedders were each pooled as their authors specify: all-MiniLM-L6-v2
(Apache 2.0), bge-small-en-v1.5 (MIT), gte-small (MIT) and snowflake-arctic-embed-s
(Apache 2.0). nDCG@25:

| Method | 3 liked | 5 | 10 | 20 |
|---|---|---|---|---|
| TF-IDF, vocabulary from the training papers (as it was) | 0.43 | 0.59 | 0.67 | 0.74 |
| TF-IDF, vocabulary from the candidates too (now) | 0.55 | 0.64 | 0.72 | 0.76 |
| Best embedding with logistic regression | 0.56 | 0.64 | 0.67 | 0.71 |
| Embedding and TF-IDF scores added | 0.62 | 0.70 | 0.76 | 0.79 |

Alone, an embedding is no better than TF-IDF with the wider vocabulary, and worse once a
reader has ten liked papers. Added together they beat either, by 12% from three liked papers
and 3% from twenty. In that combination the four embedders were within noise of each other,
so the smallest, MiniLM at 22 million parameters, would do.

On a copy of a real phone's database, holding out each of 6 liked and 7 disliked papers in
turn, no linear model placed the liked ones above the disliked ones, whether TF-IDF or
embedding. The disliked papers are inside the reader's own subjects, where one straight
boundary cannot separate them. Ranking by similarity to the nearest liked paper did, by 18
points. Thirteen papers is too few to act on, but it points the same way as the combination
above.

Not shipped. An embedder is a larger change than anything above and is a decision rather
than a fix: F-Droid builds native code from source, which rules out ONNX Runtime's prebuilt
library and leaves llama.cpp built with the NDK, which runs this family of models from GGUF
files. MiniLM would add roughly 25 to 35 MB, and embedding a day's 400 papers fits in the
nightly job. EmbeddingGemma, the strongest small embedder, is under Gemma's own terms rather
than an open source licence, so it cannot ship in an F-Droid app.

## Asking for support

The app is free and will stay free, with a Ko-fi link for readers who want to help. The risk
with any request inside an app is that it becomes the thing people remember about it, so the
design is mostly about when not to ask.

- **One permanent place.** A last row in settings with the link, a plain statement that
  nothing is locked, a way to write to the developer, and the switch for the reminder. Easy
  to find for someone looking, invisible to someone who is not.
- **A card, not a dialog.** A dialog interrupts whatever the reader opened the app to do. The
  note is the last card of the digest, after "That is today", which is the one moment the
  reader has just been given what they came for and is about to leave anyway. Only readers who
  get to the end of the digest see it. It is in the app's own green, like its other notes, so
  it reads as the app speaking rather than as an advertisement. An earlier blue version stood
  out more than a request should.
- **Five separate reading days first.** Returning is the most honest sign the app is useful:
  a first session can be curiosity, and ten papers can be opened in one evening of looking
  around, but reading on five different days is a habit. A reading day is a local day with any
  signal in the ledger the ranking already keeps, so nothing new is recorded to decide this.
- **Then it stays until answered.** The rule is simple enough to print on the card. It is
  there at the end of every digest until the reader answers it. "Not now" puts it away for
  seven calendar days, after which it is back whether or not the app was opened in between;
  "Don't ask again" puts it away for good, and so does the switch in settings. Acting on it
  keeps it away for a year, since the app cannot know whether anything came of it. An earlier
  version doubled its wait after every showing, counted in reading days; it was harder to
  explain than it was worth.
- **On Google Play it asks for a rating instead.** Play requires its own billing for payments
  to a developer, exempts only tax-exempt donations, and its policy covers any in-app button,
  link or message that leads to another way of paying. Pointing at the website or the source
  repository instead of Ko-fi would not change that if those pages carry a donate button. The
  US link-out programme exists, but needs enrolment, Google's billing library, transaction
  reporting and fees, which is out of proportion for a tip jar and would break the F-Droid
  build. So a copy installed from Play asks for what Play does allow and what most helps a free
  app there: a rating, a share, and feedback. The app checks which store installed it, so one
  build still serves every store.
- **A way to write in.** About and the support page open the reader's mail app addressed to
  hello@aftergleam.app with the version in the subject. The address the paper APIs see stays
  separate, so the two can be routed and filtered apart.

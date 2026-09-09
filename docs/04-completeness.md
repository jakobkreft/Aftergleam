# What still stands between this and a finished product

Written from three seats: someone opening the app for the first time, someone opening it every
morning, and someone coming back after a fortnight away. Ordered by what a user would notice.

---

## Done this round

**The PDF reader was rebuilt, not patched.** The old one put `detectTransformGestures` on every
page inside a LazyColumn. That detector consumes single-finger drags as pan, so the list could
only be scrolled in the gaps between pages, which is exactly what it felt like. Per-page zoom
state also lived in `remember` inside a recycling list, so zoom sprang back as soon as a page
scrolled out and in.

The replacement is a full-screen reader with:
- a pinch detector that **claims only multi-touch**, so one finger scrolls the list and two
  fingers zoom;
- **discrete zoom steps** with the page *re-rendered* at the wider size rather than a bitmap
  magnified, so text stays sharp — the whole reason to zoom a paper is to read a caption;
- **one cached `PdfRenderer` per document** instead of open-parse-close for every page, which
  is what made scrolling stutter;
- a page indicator, and the last page read remembered per paper (bounded to 100 papers, since
  an old reading position is worth nothing and preferences should not grow forever).

Verified on device: single-finger swipes in the middle of a page move 1 → 12 → 15 of 16, and
zoom steps to 150% with sharp text.

**Rotation used to drop you on Today** from whichever tab you were on: the tab lived in
`remember` rather than `rememberSaveable`. Confirmed fixed with the Library tab.

**An actual app icon**, as an adaptive vector with a themed monochrome variant, plus a proper
status-bar mark. Everything is vector, so nothing binary enters the repository and the build
stays reproducible.

**The release build had never been compiled.** It referenced a `proguard-rules.pro` that did
not exist, so `assembleRelease` failed outright. With rules written it produces **1.6 MB**
against 13.4 MB for debug. WorkManager instantiates workers reflectively, so R8 would have
stripped them and the digest would have silently stopped running at 05:00; the rules keep them
and the release dex was checked to confirm all three survive.

**Share**, **pull to refresh**, and an **About** section carrying the version and a plain
statement of what talks to the network.

---

## Still missing, roughly in order of how much it would be noticed

### The occasional user is not served at all

Someone who opens the app twice a week sees only *today*. Everything announced on the days
they missed is gone, and the app never mentions it. This is the largest remaining hole, and it
is a product hole rather than a bug.

- **A backlog.** Papers announced since the last visit, ranked, and clearly separate from
  today's digest.
- **Past digests.** The data is already in `shown`, keyed by day; nothing reads it back.

### Reading

- Text selection and copy in the abstract.
- Landscape and tablet layouts. The app is usable rotated but not laid out for it.
- Swipe gestures on cards, which is how people expect to dismiss things on a phone.

### Correctness a user would eventually hit

- The digest does not roll over at midnight while the app is open.
- No distinct state for "offline and nothing cached" as against "arXiv is down".
- A very long BibTeX import has no foreground service, so the system may kill it.

### Release engineering

None of this is started, and all of it is needed before anyone else can install the app.

- A signing config reading from environment or gradle properties, never the repository.
- fastlane metadata under `fastlane/metadata/android/en-US/`, with changelogs capped at 500
  characters.
- A reproducible-build check: same commit built in two directories, sha256 compared.
- `vcsInfo { include = false }` is set; it has not been verified against an actual second build.
- An F-Droid metadata submission, and a first tagged release.

### Accessibility

- Icon-only actions on cards have content descriptions, but nothing has been tested with
  TalkBack.
- No check at large font scales, where the card layout is most likely to break.

### Things deliberately not done

- **Catch-up mode** stays blocked on a landmark source. See M2 in the plan: it needs the
  Semantic Scholar blob, and guessing at landmarks from venue alone would be a worse feature
  than none.
- **The optional LLM layer** remains a v3 idea.
- **Dwell time as a weak positive.** Explicit ratings work, and D8 exists to warn against
  exactly this.

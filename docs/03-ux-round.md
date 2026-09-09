# UX round — plan and findings

From a session of actually using the app rather than only building it. Ordered bugs first,
then the design work that a working app made obvious.

## Bugs found and fixed

**Search results all showed 0% predicted interest, so the personalisation slider was inert.**
Two separate causes, both confirmed by test before fixing:

1. The search negative pool was `db.recentPapers(limit = 800)`, which includes the user's own
   rated papers, since those are cached like any other. The model was handed its own positives
   labelled as negatives, flattening every interest score. This is the same mistake that was
   fixed for the digest ranker and not for search.
2. The detail screen looked its confidence up in `state.cards`, today's digest. A paper opened
   from search is not in that list, so it reported 0% for every search result.

Both scores are now shown on the search card itself, so this class of bug is visible rather
than inferred.

**The PDF closed on rotation.** `remember` does not survive an activity recreation; the file
was already cached on disk, and only that flag stood between the reader and their place in the
paper. Now `rememberSaveable`.

**The keyboard would not go away.** It now dismisses on a tap anywhere off the field and on
submitting a search.

**Survey buttons moved between questions.** Abstract length varies, so laying the answers out
after the card made the target jump. In a flow whose whole point is a fast rhythm of yes and
no, that is the difference between a minute and a chore. The card now takes the remaining
space and the answers are pinned.

## Onboarding, rethought

The category picker asked the wrong question twice over: it demanded a decision before the
user had seen anything, and the answer was a filter rather than a taste. "cs.LG" describes six
thousand papers a month with nothing in common, and people are poor at naming their own
interests in the abstract.

It is now a survey. A dozen probes spread across the archive fetch real recent papers, and the
app asks the one question anyone answers reliably: would you read this? A handful of answers
gives a trained model, and the categories fall out of the papers the user kept rather than
being declared up front. Everything is skippable, a BibTeX import is offered as the faster
path, and every answer is visible and reversible in the library afterwards.

Seeds are chosen for spread, not quality: a survey that only shows machine learning papers can
only ever conclude that the user likes machine learning.

## Cards, rethought

The old card carried the full rating slider, four text actions and two metadata lines, so a
screen held one and a half papers and the list read as a wall of controls.

Triage needs four things: why the card is here, the title, a hint of the content, and one
gesture. Everything else belongs on the paper's own screen, one tap away. The reason now leads
with a colour dot for its slot, metadata is a single quiet row, and the three actions are icons
because they are universal and a word each would crowd out the paper.

## Library

Saved, Offline and Rated on one screen. The Rated shelf exists because a model trained on
ratings the user cannot see or change is not tunable, only obeyed: every rating can be moved
or removed there, and the next re-rank picks it up. Offline is derived from the PDF cache
rather than a column, so it cannot drift from what is actually on disk.

## Reminder

A reading nudge, separate from the digest worker and deliberately offline. The digest is
prepared before dawn because that is when the phone is charging on wifi; being told about it
then is useless. The reminder needs no network and no charger, and stays quiet if the digest
has already been worked through.

## Second pass, from using it again

**The survey blocked on loading the whole deck.** Twelve probes at one request every three
seconds meant staring at a progress bar for the best part of a minute before being asked
anything, when reading a single abstract takes about as long as fetching the next paper. It
now fetches one paper per probe per pass and appends to the deck while the reader answers.
Measured on device: **first question after 7 seconds instead of about 40**, and answering at a
brisk pace of roughly three seconds a card produced no waits at all. One paper per probe per
pass also interleaves fields better than two papers from each probe in turn.

**Opened papers now recede in the digest.** A `viewed` flag, set when the paper's own screen
opens: the card loses its elevation, the title drops to normal weight and a tick appears by
the authors. Deliberately not treated as a weak positive, because opening a paper and then
deciding against it is an ordinary outcome, and reading approval into it is exactly how
implicit signals poison a model. It is also excluded from backups, since it carries no
judgement worth moving between devices.

**A theme setting**: follow system, light or dark.

**Search returns to the top after reordering.** Moving the personalisation slider re-sorts the
list, and leaving the reader parked halfway down the previous ranking hides the very change
they asked to see.

## Still open

- Fetching shows a label for each stage but no true progress bar, because the arXiv call is a
  single request whose progress is not observable. A foreground-service notification would be
  the honest way to show a long import.
- Page zoom in the PDF reader, and remembering the last page read.
- Catch-up mode, still blocked on a landmark source.

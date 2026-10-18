# How it works

## Fetching

Papers come from arXiv, bioRxiv, medRxiv, OSF (PsyArXiv, SocArXiv, EdArXiv, Law Archive) and
ChemRxiv through Crossref. The app only asks the servers your subjects need. arXiv announces new
papers once each weekday, so between announcements the app re-ranks what it has instead of
fetching again. All arXiv requests share one rate limiter, one request every three seconds.

## Ranking

Titles and abstracts become TF-IDF vectors, and a logistic regression is trained on the phone
from your reactions. It retrains when you react. Nothing about your reading leaves the phone.

| Signal | Weight |
|---|---|
| More like this | +0.95 |
| Read three or more PDF pages | +0.9 |
| Shared | +0.85 |
| PDF open for 20 seconds | +0.7 |
| Saved | +0.6 |
| 15 seconds on the paper | +0.4 |
| Opened | +0.25 |
| Less like this | -1.0 |

When a paper has several signals, the strongest one counts fully and the others add a little.
Scrolling past a paper is not used for training, because it would swamp everything else.

A venue in arXiv's comments ("NeurIPS 2025", "accepted at ICLR") multiplies the score by up to
1.35. It multiplies rather than adds, so a well published paper on a topic you dislike still
ranks low.

## The daily digest

Scores are compared within the day's papers, then sampled rather than taken from the top, so
the digest changes from day to day and the model does not narrow onto one topic. A topic bandit
shares the slots among your subjects. Most cards are matches; a few are near misses, and one
comes from a neighbouring field. Papers that mention one of your keywords, matched as written,
can take up to half of the matches. A keyword can combine words: "lanosterol + hypoxia" needs
both.

## Notifications

Two kinds, both off until you turn them on. The daily note names the first paper of the
digest, at an hour you choose, and only on days you have not opened the app. A keyword with
its bell on is checked every six hours; a new paper that mentions it is announced once,
between 08:00 and 21:00, and later ones the same day join the same note quietly.

## Reading

PDFs are drawn with Android's own PdfRenderer, which also does the search. The reader view uses
arXiv's HTML version of a paper, shown with scripts off and figures kept on the phone. Downloads
are kept in the app's own storage, so clearing the cache does not remove them.

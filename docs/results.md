# Results

Measurements that decided how the app works. The scripts are in `prototype/`.

## Can abstracts alone find a reader's papers? (E1)

A real library of 38 papers, each held out in turn and ranked against 301 random arXiv papers
from the same years. How often the held-out paper landed in the top 10:

| Method | cs.CV only (24 papers) | All (38) |
|---|---|---|
| Random | 0.04 | 0.05 |
| TF-IDF + logistic regression | 0.88 | 0.82 |
| MiniLM embedding + logistic regression | 0.79 | 0.79 |

The median held-out paper ranked 2nd of 301. TF-IDF and MiniLM are not significantly different
at this size, so the app uses TF-IDF: no model download, no native code, and the reasons it
gives are real words from the abstract.

## Would an embedding model rank better? (E2)

The same library against 1,500 cs.CV and cs.LG papers, with a few papers given as liked.
nDCG@25 over 25 random splits:

| Method | 3 liked | 5 | 10 | 20 |
|---|---|---|---|---|
| TF-IDF, vocabulary from training papers only | 0.43 | 0.59 | 0.67 | 0.74 |
| TF-IDF, vocabulary including the day's papers (shipped) | 0.55 | 0.64 | 0.72 | 0.76 |
| Best of four small embedders | 0.56 | 0.64 | 0.67 | 0.71 |
| Embedding and TF-IDF added | 0.62 | 0.70 | 0.76 | 0.79 |

An embedding alone is no better. Combined with TF-IDF it helps, 3 to 12%, but it would add
25 to 35 MB and native code that F-Droid has to build, so it is not in the app.

## Does the digest deliver what the model finds?

A simulation runs the app's own ranker for 40 mornings with simulated readers. Share of the
best papers (top 3% for that reader) that reached the digest, before and after fixing the
sampler, the topic bandit and the vocabulary:

| Reader | Before | After |
|---|---|---|
| Broad machine learning | 0.19 | 0.25 |
| Robotics | 0.34 | 0.61 |
| Numerical analysis | 0.18 | 0.43 |
| Security | 0.43 | 0.65 |
| Machine learning and neuroscience | 0.21 | 0.38 |

Before the fixes the model's own top 25 was good, but the digest built from it was worse than a
random pick of the same day's papers. The main cause: the scores were too close together for
the sampler to tell them apart.

## Citations and venues

OpenAlex could not supply citations for arXiv preprints: of 120 cs.LG papers from March 2025,
91% had zero citations 18 months later and none had more than three. One of them, an ICLR oral,
had 3 in OpenAlex and 99 in Semantic Scholar. arXiv's own comments field names a venue for 35%
of papers, with no extra request, so venue is the quality signal instead.

## Keywords must match literally

The first version matched keywords through the ranker's word list, which drops short words and
splits at hyphens. Checked against real papers:

| Keyword | Matched on | Wrong |
|---|---|---|
| H-Net | "net" | 21 of 35 |
| U-Net | "net" | 17 of 61 |
| Sentinel-2 | "sentinel" | 7 of 47 |
| C. elegans | "elegans" | 10 of 79 |
| RL | nothing | found none of 41 |

For ordinary words the two methods agreed, so keywords now match as written.

## Combined keywords

arXiv's search takes AND and OR: "lanosterol + hypoxia" found 2 recent papers, against 116 for
hypoxia alone. Crossref's does not. Asked for "hypoxia sleep apnea", it returned 60 papers and
none mentioned both. So Crossref is asked once for each part of a keyword, and the phone keeps
the papers that mention every part.

## A phrase or separate words

Of the arXiv and Crossref results that contain every word of a search, how many say the words
as one phrase:

| Search | arXiv | Crossref |
|---|---|---|
| sleep apnea | 92% | 100% |
| large language model | 98% | 100% |
| theory of mind | 88% | 78% |
| attention mechanism | 88% | 67% |
| CRISPR gene editing | 7% | 19% |
| protein folding dynamics | 15% | 5% |
| cholesterol hypoxia | 0% | 0% |
| hypoxia sleep apnea | 0% | 0% |

Names are at 67% or more and combinations at 19% or less, so words are kept together when at
least 40% of the results say them together. Asked for "lanosterol hypoxia", Crossref returned 60
papers and none mentioned both, so its results are now held to every word, as arXiv's are.

## Reader view

Most new arXiv papers have an HTML version: 30 of 30 new mathematics papers, 38 of 43 in cs.CY
and 10 of 13 in econ.GN. Laid out at phone width, 63 of 64 papers from twelve fields showed
nothing cut off at the side. The one exception is a passage arXiv's own conversion garbled.
Text extracted from the PDF instead was not readable enough to use: lines broken mid-word,
labels from inside figures, captions out of place and no figures.

Search in a PDF takes about a millisecond a page with Android's own renderer.

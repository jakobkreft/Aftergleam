# Prototype

The Python harness used to decide how the app ranks papers. It is not needed to build or run
the app. It exists so that the ranking choices can be measured rather than assumed, and so
they can be measured again.

It answered the question the whole project depended on: do abstracts carry enough signal to
tell a reader's papers apart from everything else? They do. The results are in
[`docs/03-e1-results.md`](../docs/03-e1-results.md).

## Setup

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

`data/`, `models/` and `out/` are not in the repository. They are large and can be rebuilt.

## What is here

| File | Purpose |
|---|---|
| `aftergleam/harvest.py` | Bulk-harvests arXiv metadata over OAI-PMH |
| `aftergleam/build_corpus.py` | Builds the evaluation corpus from harvested metadata |
| `aftergleam/library.py` | Parses a BibTeX or RIS library and resolves entries to arXiv |
| `aftergleam/embed.py` | Sentence embedder on ONNX Runtime, the comparison TF-IDF was measured against |
| `aftergleam/e1.py` | The evaluation: held-out library papers against random ones |
| `run_e1.py` | Runs the evaluation end to end |

## Running the evaluation

`run_e1.py` needs a resolved library in `data/library_resolved.json` and a corpus in
`data/corpus.parquet`. Build them with `library.py` and `build_corpus.py` from your own BibTeX
export first.

```sh
.venv/bin/python run_e1.py --control cs.CV --distract 500 --seeds 5
```

arXiv asks for no more than one request every three seconds, and the harvester keeps to that.
A full harvest is slow on purpose.

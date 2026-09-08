"""Run E1 end to end: library -> positives, corpus -> negatives, then the gate metric.

    python run_e1.py                 # all categories + the cs.CV control
    python run_e1.py --control cs.LG # a different narrow control
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

from aftergleam import e1

DATA = Path(__file__).parent / "data"


def load_positives() -> pd.DataFrame:
    resolved = json.load(open(DATA / "library_resolved.json"))
    df = pd.DataFrame(resolved)
    df["primary"] = df.categories.str.split().str[0]
    # A library is a reading list, not a random sample: the same paper can appear twice
    # under different bib keys (the Knuth volumes did). Dedupe on arXiv id.
    return df.drop_duplicates("arxiv_id").reset_index(drop=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--control", default="cs.CV",
                    help="narrow category for the control run (the result that counts)")
    ap.add_argument("--distract", type=int, default=500)
    ap.add_argument("--seeds", type=int, default=5)
    a = ap.parse_args()

    pos = load_positives()
    corpus = pd.read_parquet(DATA / "corpus.parquet")
    # Never let a library paper leak into the distractor pool — it would be scored a
    # false positive while actually being a true one, quietly depressing every method.
    corpus = corpus[~corpus.arxiv_id.isin(set(pos.arxiv_id))].reset_index(drop=True)

    print(f"positives: {len(pos)}   corpus: {len(corpus)}")
    print("positive categories:", pos["primary"].value_counts().head(6).to_dict())

    e1.report(pos, corpus, control_category=None,
              n_distract=a.distract, n_seeds=a.seeds)
    e1.report(pos, corpus, control_category=a.control,
              n_distract=a.distract, n_seeds=a.seeds)


if __name__ == "__main__":
    main()

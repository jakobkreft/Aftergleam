"""Build the E1 corpus: negatives and distractors sampled from real arXiv traffic.

The corpus does NOT need to contain the user's library. Positives come from their own
BibTeX and are fetched separately; this corpus supplies the "easy negatives" and the
500 distractors that precision@10 is measured against.

That distinction keeps the harvest small: a recent window is enough, because a random
paper from July 2026 is just as valid a negative as one from 2025.

Usage:
    python -m aftergleam.build_corpus --frm 2026-07-01 --max 30000
"""

from __future__ import annotations

import argparse
from pathlib import Path

from .harvest import harvest, to_frame

DATA = Path(__file__).resolve().parent.parent / "data"

# Primary categories we care about for E1. cs.LG is the narrow-category control:
# the hard case where every abstract shares a vocabulary.
TARGETS = {"cs.LG", "cs.CV", "q-bio.NC"}


def primary(cats: str) -> str:
    return (cats or "").split()[0] if cats else ""


def build(sets: list[str], frm: str, until: str | None, cap: int) -> None:
    DATA.mkdir(exist_ok=True)
    frames = []
    for s in sets:
        print(f"[{s}] harvesting from {frm}...", flush=True)
        df = to_frame(harvest(oai_set=s, frm=frm, until=until, max_records=cap, verbose=True))
        if df.empty:
            print(f"[{s}] no records")
            continue
        df["primary"] = df.categories.map(primary)
        frames.append(df)
        print(f"[{s}] {len(df)} records", flush=True)

    if not frames:
        raise SystemExit("harvest returned nothing")

    import pandas as pd

    all_df = pd.concat(frames, ignore_index=True).drop_duplicates("arxiv_id")
    all_df.to_parquet(DATA / "corpus_all.parquet", index=False)

    tgt = all_df[all_df["primary"].isin(TARGETS)].copy()
    tgt.to_parquet(DATA / "corpus.parquet", index=False)

    print(f"\nharvested {len(all_df)} unique records")
    print(f"in target categories: {len(tgt)}")
    print(tgt["primary"].value_counts().to_string())
    print(f"\nabstract non-empty: {(tgt.abstract.str.len() > 0).mean() * 100:.1f}%")
    print(f"median abstract chars: {int(tgt.abstract.str.len().median())}")
    print(f"wrote {DATA / 'corpus.parquet'}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--sets", nargs="+", default=["cs", "q-bio"])
    ap.add_argument("--frm", default="2026-07-01")
    ap.add_argument("--until", default=None)
    ap.add_argument("--max", type=int, default=30000, dest="cap")
    a = ap.parse_args()
    build(a.sets, a.frm, a.until, a.cap)

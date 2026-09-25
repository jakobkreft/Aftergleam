"""Builds simulated readers for the digest simulation (DigestSimulation.kt in the app's tests).

The E1 and E2 experiments score a ranking once. They cannot see what the digest does over
weeks: what the sampler lets through, whether the topic bandit spends its slots well, whether
the feed narrows. That needs a reader who reacts, day after day, to what they were shown.

A simulated reader has interests nobody tells the app about: two or three centres in a sentence
embedding space (bge-small, deliberately not the app's own representation). How much they would
like a paper is its similarity to the nearest centre. The app only ever learns this the way it
would from a person, through the signals the reader gives the cards they are shown.

Writes three files to out/sim/:
  papers.tsv    id, day, categories (primary first), title, abstract, comments, journal_ref
  personas.tsv  name, subscribed categories, number of interests
  utility.tsv   persona, paper id, utility, index of the nearest interest

    .venv/bin/python sim_prep.py
"""
from __future__ import annotations
import sys
from pathlib import Path
import numpy as np, pandas as pd
sys.path.insert(0, str(Path(__file__).parent))
from e2 import embed  # noqa: E402

ROOT = Path(__file__).parent
OUT = ROOT / "out" / "sim"
DAYS = 40
OUTSIDE_SHARE = 0.04       # of out-of-field papers a reader's phone also holds, for the bridge
NEIGHBOURS = 30            # papers averaged into one interest centre
PERSONAS = {               # subscribed categories, number of interests
    "broad-ml": (["cs.CV", "cs.CL", "cs.LG", "cs.AI"], 3),
    "robotics": (["cs.RO"], 2),
    "numerics": (["math.NA", "cs.NA"], 2),
    "security": (["cs.CR"], 2),
    "neuro": (["q-bio.NC"], 2),
}
CROSS = {"ml-neuro": ["cs.LG", "q-bio.NC"]}   # subscribed categories, one interest in each


def clean(s: str) -> str:
    return " ".join(str(s or "").split())


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(1)
    d = pd.read_parquet(ROOT / "data" / "corpus_all.parquet")
    d = d[d["abstract"].str.len() > 200].reset_index(drop=True)
    d["cats"] = [[p] + [c for c in cs.split() if c != p]
                 for p, cs in zip(d["primary"], d["categories"])]
    d["day"] = rng.integers(0, DAYS, len(d))

    world = set()
    scope = {}
    for name, (subs, _) in PERSONAS.items():
        inside = d.index[[any(c in subs for c in cs) for cs in d["cats"]]]
        outside = d.index.difference(inside)
        extra = rng.choice(outside, int(len(outside) * OUTSIDE_SHARE), replace=False)
        scope[name] = (np.array(inside), np.array(extra))
        world.update(inside); world.update(extra)
        print(f"{name:10} {len(inside):6} in field, {len(extra):5} outside")
    world = np.array(sorted(world))
    print(f"embedding {len(world)} papers")

    cache = OUT / "emb_bge.npy"
    ids = d.loc[world, "arxiv_id"].to_numpy()
    if cache.exists() and (OUT / "emb_ids.txt").read_text().split() == list(ids):
        E = np.load(cache)
    else:
        texts = [f"{t}. {a}" for t, a in zip(d.loc[world, "title"], d.loc[world, "abstract"])]
        E = embed("bge-small", texts)
        np.save(cache, E)
        (OUT / "emb_ids.txt").write_text("\n".join(ids))
    row = {int(i): k for k, i in enumerate(world)}

    with open(OUT / "papers.tsv", "w") as f:
        for i in world:
            r = d.loc[i]
            f.write("\t".join([r.arxiv_id, str(r.day), " ".join(r.cats), clean(r.title),
                               clean(r.abstract), clean(r.comments), clean(r.journal_ref)]) + "\n")

    with open(OUT / "personas.tsv", "w") as fp, open(OUT / "utility.tsv", "w") as fu:
        for name, (subs, k) in PERSONAS.items():
            inside, extra = scope[name]
            Ei = E[[row[int(i)] for i in inside]]
            def centre(seed):
                near = np.argsort(-(Ei @ seed))[:NEIGHBOURS]
                c = Ei[near].mean(0)
                return c / np.linalg.norm(c)
            centres = [centre(Ei[rng.integers(len(Ei))])]
            while len(centres) < k:
                # Interests a reader holds at once are distinct, or they would be one interest:
                # of fifty random candidates, the one least like any interest already chosen.
                # A fixed similarity cut-off does not work, because how similar two unrelated
                # abstracts look depends on the embedder.
                options = [centre(Ei[j]) for j in rng.integers(len(Ei), size=50)]
                centres.append(min(options, key=lambda c: max(c @ o for o in centres)))
            C = np.stack(centres)
            sims = [float(C[i] @ C[j]) for i in range(len(C)) for j in range(i + 1, len(C))]
            print(f"{name:10} {len(C)} interests, similarity between them {sims}")
            fp.write(f"{name}\t{' '.join(subs)}\t{len(centres)}\n")
            for i in np.concatenate([inside, extra]):
                s = C @ E[row[int(i)]]
                fu.write(f"{name}\t{d.loc[i, 'arxiv_id']}\t{s.max():.5f}\t{int(s.argmax())}\n")
    # A reader of two unrelated fields, one far busier than the other. This is the reader the
    # topic bandit exists for: whichever field they happen to engage with first can take over
    # the model, and the quieter one then never scores high enough to be shown. One interest
    # is seeded in each field. Its own random stream, and only papers already embedded, so the
    # readers above are unchanged.
    xr = np.random.default_rng(2)
    with open(OUT / "personas.tsv", "a") as fp, open(OUT / "utility.tsv", "a") as fu:
        for name, subs in CROSS.items():
            cats = d.loc[world, "cats"]
            inside = world[[any(c in subs for c in cs) for cs in cats]]
            others = world[[not any(c in subs for c in cs) for cs in cats]]
            extra = xr.choice(others, int(len(others) * OUTSIDE_SHARE), replace=False)
            centres = []
            for sub in subs:
                own = inside[[d.loc[i, "cats"][0] == sub for i in inside]]
                Eo = E[[row[int(i)] for i in own]]
                seed = Eo[xr.integers(len(Eo))]
                near = np.argsort(-(Eo @ seed))[:NEIGHBOURS]
                c = Eo[near].mean(0)
                centres.append(c / np.linalg.norm(c))
            C = np.stack(centres)
            print(f"{name:10} {len(inside):6} in field, {len(extra):5} outside, "
                  f"similarity between interests {float(C[0] @ C[1]):.2f}")
            fp.write(f"{name}\t{' '.join(subs)}\t{len(C)}\n")
            for i in np.concatenate([inside, extra]):
                s = C @ E[row[int(i)]]
                fu.write(f"{name}\t{d.loc[i, 'arxiv_id']}\t{s.max():.5f}\t{int(s.argmax())}\n")
    print("written to", OUT)


if __name__ == "__main__":
    main()

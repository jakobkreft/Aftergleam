"""E2b: the same comparison on a real reader's phone, with their real reactions.

E2 uses a thesis bibliography, which is one coherent topic and the easy case for TF-IDF. This
reader follows agents, language models, diffusion and vision at once. Two questions:

  1. Spread: does the method separate today's candidates at all? The app's scores sat between
     0.26 and 0.40 for every paper, which left the order to the venue bonus.
  2. Quality, leave one out: hold out each liked and each disliked paper in turn, train on the
     rest, and see where it lands among today's candidates.
"""
import os, random, sqlite3, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
from pipeline import Tfidf, LogReg
from e2 import embed, dense_lr

if len(sys.argv) < 2:
    sys.exit("usage: e2_device.py COPY_OF_aftergleam.db [model]")
DB = os.path.expanduser(sys.argv[1])
W = dict(LIKED=.95, READ_PAGES=.9, SHARED=.85, DOWNLOADED=.7, SAVED=.6, DWELLED=.4,
         OPENED=.25, PASSED=-.05, DISLIKED=-1.0)

def label(sigs):
    if "DISLIKED" in sigs: return 0.0
    pos = sorted([W[s] for s in sigs if W[s] > 0], reverse=True)
    if not pos: return 0.0 if "PASSED" in sigs else None
    d = 1.0
    for w in pos[1:]: d *= (1 - w)
    return pos[0] + (1 - pos[0]) * 0.5 * (1 - d)

c = sqlite3.connect(DB)
sigs = {}
for p, s in c.execute("SELECT paper_id, signal FROM signals"): sigs.setdefault(p, set()).add(s)
rows = c.execute("SELECT id, title, abstract FROM papers ORDER BY published DESC").fetchall()
ids = [r[0] for r in rows]; idx = {p: i for i, p in enumerate(ids)}
texts = [f"{t}. {a}" for _, t, a in rows]
rated = {p: label(s) for p, s in sigs.items() if label(s) is not None and p in idx}
cands = [idx[p] for p in ids[:400] if p not in rated]
older = [idx[p] for p in ids[400:] if p not in rated]
print(f"device: {len(ids)} papers, {len(rated)} rated, {len(cands)} candidates today")

MODEL = sys.argv[2] if len(sys.argv) > 2 else "gte-small"
E = embed(MODEL, texts)

def scorers(train_ids, train_y, rng):
    negs = rng.sample(older, min(len(older), 10 * len(train_ids)))
    tr = [idx[p] for p in train_ids] + negs; y = list(train_y) + [0.0] * len(negs)
    out = {}
    v = Tfidf().fit([texts[i] for i in tr])
    m = LogReg(len(v.terms_)).fit([v.transform(texts[i]) for i in tr], y)
    out["tfidf (app)"] = lambda I: np.array([m.predict(v.transform(texts[i])) for i in I])
    v2 = Tfidf().fit([texts[i] for i in tr + cands])
    m2 = LogReg(len(v2.terms_)).fit([v2.transform(texts[i]) for i in tr], y)
    out["tfidf (device vocab)"] = lambda I: np.array([m2.predict(v2.transform(texts[i])) for i in I])
    f = dense_lr(E[tr], y)
    out[f"{MODEL} logreg"] = lambda I: f(E[I])
    likes = [idx[p] for p, l in zip(train_ids, train_y) if l >= 0.6]
    out[f"{MODEL} nearest like"] = lambda I: (E[I] @ E[likes].T).max(1) if likes else np.zeros(len(I))
    return out

rng = random.Random(7)
print("\n1. Spread over today's candidates (all of the reader's reactions used)")
S = scorers(list(rated), list(rated.values()), rng)
for name, fn in S.items():
    s = fn(cands)
    print(f"  {name:24} p10 {np.percentile(s,10):.3f}  p50 {np.median(s):.3f}  p90 {np.percentile(s,90):.3f}"
          f"   top-25 vs rest gap {np.sort(s)[-25:].mean() - np.median(s):.3f}")

print("\n2. Leave one out: where each held-out paper lands among today's candidates")
explicit = [p for p in rated if "LIKED" in sigs[p] or "DISLIKED" in sigs[p]]
res = {}
for p in explicit:
    rest = [q for q in rated if q != p]
    sc = scorers(rest, [rated[q] for q in rest], random.Random(hash(p) % 999))
    for name, fn in sc.items():
        s = fn(cands + [idx[p]])
        pct = (s[:-1] < s[-1]).mean()          # share of today's papers it outranks
        res.setdefault(name, {"liked": [], "disliked": []})["liked" if "LIKED" in sigs[p] else "disliked"].append(pct)
for name, r in res.items():
    lk, dk = np.array(r["liked"]), np.array(r["disliked"])
    print(f"  {name:24} liked outrank {lk.mean():.0%} of candidates   disliked outrank {dk.mean():.0%}"
          f"   separation {lk.mean() - dk.mean():+.0%}   (n={len(lk)}+{len(dk)})")

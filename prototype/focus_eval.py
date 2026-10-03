"""Would a typed interest ("earth observation, satellite photos, anomaly detection") help?

The app learns from subjects and reactions. A scientist deep in one narrow topic may want to
say it outright. This measures, for eight narrow interests typed the way people type them
(casual, with typos), how well several ways of using the text find the papers that interest
is really about, from a day's candidates in the reader's subjects:

  subjects only        the app as it is: the chosen subjects' seed text trains the model
  focus as seed        the typed text added as one more liked pseudo-document
  focus match          lexical match between the typed text and each paper
  + expansion          the match widened with words from the papers that match it best
  + typo fix           query words missing from the vocabulary mapped to their nearest word
  blend                the expanded match combined with the model, as the app would rank
  semantic (MiniLM)    a small sentence embedder, for reference

Ground truth is a separate embedder (bge-small) scoring each paper against a fuller,
correctly spelt description of what the scientist means; the top 2% of their subjects'
papers count as relevant. It is not the app's representation and not MiniLM's.

Cold start (no reactions) and warm (five liked papers) are both measured.

    .venv/bin/python focus_eval.py
"""
from __future__ import annotations
import re, sys
from collections import Counter
from pathlib import Path
import numpy as np, pandas as pd
sys.path.insert(0, str(Path(__file__).parent))
from pipeline import Tfidf, LogReg, terms  # noqa: E402
from e2 import embed  # noqa: E402

ROOT = Path(__file__).parent
OUT = ROOT / "out" / "focus"
POOL, DEVICE_EXTRA, POOLS, WARM_LIKES = 400, 1500, 20, 5
BGE_QUERY = "Represent this sentence for searching relevant passages: "

# key, subscribed categories, subject topic, what the reader types, what they mean
PERSONAS = [
    ("earth-obs", ["cs.CV", "cs.MM"], "vision",
     "earth observation computer vision, satelite photos, anommaly detection",
     "computer vision for earth observation and remote sensing satellite imagery, "
     "including anomaly and change detection"),
    ("mol-gnn", ["cs.LG", "stat.ML"], "ml",
     "graph neural nets for molecules, drug discovery",
     "graph neural networks for molecular property prediction and drug discovery"),
    ("med-seg", ["cs.CV", "cs.MM"], "vision",
     "medical image segmentation MRI CT",
     "segmentation of medical images such as MRI and CT scans"),
    ("fed-priv", ["cs.CR"], "security",
     "federated learning privacy",
     "privacy attacks and defences in federated learning, differential privacy"),
    ("legged", ["cs.RO"], "robotics",
     "legged robots locomotion RL",
     "reinforcement learning for legged robot locomotion, quadrupeds and humanoids"),
    ("low-res-mt", ["cs.CL"], "llm",
     "low resource languages translation",
     "machine translation and language models for low-resource languages"),
    ("multigrid", ["math.NA", "cs.MS"], "numerics",
     "multigrid preconditioners",
     "multigrid methods and preconditioners for large sparse linear systems from PDEs"),
    ("spiking", ["q-bio.NC"], "neuro",
     "spiking neural networks neuromorphic",
     "spiking neural network models and neuromorphic computing"),
]


def topic_seed(key: str) -> str:
    src = (ROOT.parent / "android/app/src/main/java/si/jakobkreft/aftergleam/data/Topics.kt").read_text()
    for k, label, _, seed in re.findall(
            r'Topic\(\s*"([^"]+)",\s*"([^"]+)",\s*listOf\(([^)]*)\),\s*"([^"]+)"', src):
        if k == key:
            return f"{label}. {seed}"
    raise KeyError(key)


def lev(a: str, b: str, cap: int = 3) -> int:
    if abs(len(a) - len(b)) > cap: return cap + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]


def fix_typos(query: str, vocab_df: dict[str, int]) -> str:
    """Each word the device's papers never use, replaced by the commonest word within an edit or two."""
    out = []
    for w in re.findall(r"[A-Za-z0-9-]+|[^A-Za-z0-9-]+", query):
        lw = w.lower()
        if not re.fullmatch(r"[a-z][a-z-]{3,}", lw) or vocab_df.get(lw, 0) >= 2:
            out.append(w); continue
        limit = 1 if len(lw) <= 5 else 2
        best = max(((t, df) for t, df in vocab_df.items()
                    if '_' not in t and df >= 3 and abs(len(t) - len(lw)) <= limit
                    and lev(lw, t, limit) <= limit), key=lambda x: x[1], default=None)
        out.append(best[0] if best else w)
    return "".join(out)


def as_dense(v, dim):
    idx, vals = v
    x = np.zeros(dim); x[idx] = vals; return x


def z(x):
    x = np.asarray(x, float); s = x.std()
    return (x - x.mean()) / s if s > 1e-12 else np.zeros_like(x)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    d = pd.read_parquet(ROOT / "data" / "corpus_all.parquet")
    d = d[d["abstract"].str.len() > 200].drop_duplicates("arxiv_id").reset_index(drop=True)
    E = np.load(ROOT / "out/sim/emb_bge.npy")
    ids = (ROOT / "out/sim/emb_ids.txt").read_text().split()
    row = {a: i for i, a in enumerate(ids)}
    d = d[d["arxiv_id"].isin(row)].reset_index(drop=True)
    d["text"] = d["title"].str.strip() + ". " + d["abstract"].str.strip()
    cats = [set(c.split()) for c in d["categories"]]
    Eb = E[[row[a] for a in d["arxiv_id"]]]

    qb = embed("bge-small", [BGE_QUERY + p[4] for p in PERSONAS])
    universes = [np.array([i for i, c in enumerate(cats) if c & set(p[1])]) for p in PERSONAS]
    allu = np.unique(np.concatenate(universes))
    cache = OUT / "minilm.npy"
    if cache.exists() and np.load(OUT / "minilm_rows.npy").tolist() == allu.tolist():
        Em_u = np.load(cache)
    else:
        Em_u = embed("minilm", d["text"].iloc[allu].tolist())
        np.save(cache, Em_u); np.save(OUT / "minilm_rows.npy", allu)
    Em = {int(i): Em_u[k] for k, i in enumerate(allu)}
    qm = embed("minilm", [p[3] for p in PERSONAS])

    rng = np.random.default_rng(7)
    methods = ["subjects only", "focus as seed", "focus match", "+ expansion", "+ typo fix",
               "blend", "blend m0.25", "blend m0.5", "semantic (MiniLM)"]
    rows = []
    for pi, (key, subs, topic, typed, meant) in enumerate(PERSONAS):
        U = universes[pi]
        truth = Eb[U] @ qb[pi]
        n_rel = max(15, int(0.02 * len(U)))
        rel_set = set(U[np.argsort(-truth)[:n_rel]].tolist())
        if pi == 0 or "--titles" in sys.argv:
            print(f"\n{key}: {len(U)} papers in subjects, {n_rel} relevant. Top by ground truth:")
            for i in U[np.argsort(-truth)[:4]]: print("   ", d["title"].iloc[i][:90])
        seed = topic_seed(topic)
        for warm in (False, True):
            scores = {m: [] for m in methods}
            for _ in range(POOLS):
                pool = rng.choice(U, POOL, replace=False)
                rest = np.setdiff1d(U, pool)
                older = rng.choice(rest, min(DEVICE_EXTRA, len(rest)), replace=False)
                device = np.concatenate([pool, older])
                likes = []
                if warm:
                    cand = [i for i in rest if i in rel_set]
                    likes = list(rng.choice(cand, min(WARM_LIKES, len(cand)), replace=False))
                is_rel = np.array([i in rel_set for i in pool])
                texts = d["text"]

                def model(extra_docs):
                    rated = [(seed, 0.7)] + [(texts.iloc[i], 0.95) for i in likes] + extra_docs
                    negs = rng.choice(np.setdiff1d(older, likes), 10 * len(rated), replace=False)
                    docs = [t for t, _ in rated] + [texts.iloc[i] for i in negs]
                    y = [w for _, w in rated] + [0.0] * len(negs)
                    v = Tfidf().fit(docs + [texts.iloc[i] for i in pool])
                    m = LogReg(len(v.terms_)).fit([v.transform(t) for t in docs], y)
                    return np.array([m.predict(v.transform(texts.iloc[i])) for i in pool])

                base = model([])
                scores["subjects only"].append(base)
                scores["focus as seed"].append(model([(typed, 0.95)]))

                vec = Tfidf(min_df=2, max_df_ratio=0.5).fit([texts.iloc[i] for i in device])
                dim = len(vec.terms_)
                vocab_df = {t: int(df) for t, df in zip(vec.terms_, vec.df_)}
                Dsp = [vec.transform(texts.iloc[i]) for i in device]

                def dots(qv, docs):
                    return np.array([float(qv[ix] @ vl) if len(ix) else 0.0 for ix, vl in docs])

                def match(q, expand):
                    qv = as_dense(vec.transform(q), dim)
                    if not qv.any(): return np.zeros(POOL)
                    if expand:
                        s = dots(qv, Dsp)
                        top = np.argsort(-s)[:10]
                        top = top[s[top] > 0]
                        if len(top):
                            c = np.zeros(dim)
                            for t in top:
                                ix, vl = Dsp[t]; c[ix] += vl
                            c /= len(top)
                            keep = np.argsort(-c)[:50]
                            cc = np.zeros(dim); cc[keep] = c[keep]
                            cc /= np.linalg.norm(cc) or 1
                            qv = qv / np.linalg.norm(qv) + 0.6 * cc
                    return dots(qv, Dsp[:POOL])

                scores["focus match"].append(match(typed, False))
                scores["+ expansion"].append(match(typed, True))
                fixed = fix_typos(typed, vocab_df)
                fx = match(fixed, True)
                scores["+ typo fix"].append(fx)
                scores["blend"].append(z(base) + z(fx))
                scores["blend m0.25"].append(0.25 * z(base) + z(fx))
                scores["blend m0.5"].append(0.5 * z(base) + z(fx))
                scores["semantic (MiniLM)"].append(np.array([Em[int(i)] @ qm[pi] for i in pool]))
                for m in methods:
                    s = scores[m][-1]
                    top = np.argsort(-s)[:25]
                    scores[m][-1] = is_rel[top].mean() / min(1.0, is_rel.sum() / 25)
            for m in methods:
                rows.append(dict(persona=key, warm=warm, method=m, recall25=np.mean(scores[m])))
        if pi == 0:
            print(f"   typed:  {typed!r}\n   fixed:  {fix_typos(typed, vocab_df)!r}")

    r = pd.DataFrame(rows)
    for warm in (False, True):
        t = r[r.warm == warm].pivot(index="persona", columns="method", values="recall25")[methods]
        t.loc["mean"] = t.mean()
        print(f"\n{'Five liked papers' if warm else 'Cold start, no reactions'}: share of the "
              "best possible digest that is relevant (top 25 of 400 candidates)")
        print(t.round(2).to_string())
    r.to_csv(OUT / "results.csv", index=False)


if __name__ == "__main__":
    main()

"""E2: does a dense embedding rank a reader's papers better than the app's TF-IDF model?

E1 (docs/03-e1-results.md) compared TF-IDF against one small embedder on a single split and
found no significant difference. This asks the question the app actually faces:

  * few ratings: 3, 5, 10 and 20 liked papers, which is the first weeks of use;
  * hard candidates: other papers from the reader's own categories, not random arXiv,
    because the digest ranks inside the subjects the reader chose;
  * many splits, so a difference can be told from noise.

Each embedder is pooled the way its authors specify. E1's harness mean-pooled everything,
which is right for MiniLM and GTE and wrong for BGE and Arctic, which use the [CLS] token.
"""
from __future__ import annotations
import json, math, random, sys, time
from pathlib import Path
import numpy as np, pandas as pd, onnxruntime as ort
from tokenizers import Tokenizer
sys.path.insert(0, str(Path(__file__).parent))
from pipeline import Tfidf, LogReg, terms  # noqa: E402  the app's ranking, replicated

ROOT = Path(__file__).parent
MODELS = {  # directory, pooling
    "minilm": ("minilm", "mean"),
    "bge-small": ("bge-small", "cls"),
    "gte-small": ("gte-small", "mean"),
    "arctic-s": ("arctic-s", "cls"),
}

def embed(model: str, texts: list[str], batch=32, max_len=256) -> np.ndarray:
    d, pooling = MODELS[model]
    tok = Tokenizer.from_file(str(ROOT / "models" / d / "tokenizer.json"))
    tok.enable_truncation(max_length=max_len); tok.enable_padding()
    so = ort.SessionOptions(); so.intra_op_num_threads = 0
    sess = ort.InferenceSession(str(ROOT / "models" / d / "onnx" / "model.onnx"), so)
    names = {i.name for i in sess.get_inputs()}
    out = []
    for i in range(0, len(texts), batch):
        enc = tok.encode_batch(texts[i:i + batch])
        ids = np.array([e.ids for e in enc], dtype=np.int64)
        mask = np.array([e.attention_mask for e in enc], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in names: feed["token_type_ids"] = np.zeros_like(ids)
        h = sess.run(None, feed)[0]
        if pooling == "cls":
            v = h[:, 0]
        else:
            m = mask[..., None].astype(np.float32)
            v = (h * m).sum(1) / np.clip(m.sum(1), 1e-9, None)
        v = v / (np.linalg.norm(v, axis=1, keepdims=True) + 1e-9)
        out.append(v.astype(np.float32))
    return np.vstack(out)

def dense_lr(X, y, epochs=400, lr=2.0, l2=1e-3):
    """The app's loss on dense vectors: soft labels, class weights by positive mass, L2."""
    y = np.asarray(y, dtype=np.float64); n = len(y)
    pos = max(y.sum(), 1e-3); neg = max(n - pos, 1e-3)
    sw = y * (n / (2 * pos)) + (1 - y) * (n / (2 * neg))
    w = np.zeros(X.shape[1]); b = 0.0
    for _ in range(epochs):
        p = 1 / (1 + np.exp(-(X @ w + b)))
        g = (p - y) * sw
        w -= lr * (X.T @ g / n + l2 * w); b -= lr * g.mean()
    return lambda Z: 1 / (1 + np.exp(-(Z @ w + b)))

def metrics(scores, is_pos, k=25):
    order = np.argsort(-scores); rel = is_pos[order]
    npos = is_pos.sum()
    # AUC by ranks
    ranks = np.empty(len(scores)); ranks[order] = np.arange(len(scores), 0, -1)
    auc = (ranks[is_pos].sum() - npos * (npos + 1) / 2) / (npos * (len(scores) - npos))
    dcg = (rel[:k] / np.log2(np.arange(2, k + 2))).sum()
    idcg = (1 / np.log2(np.arange(2, min(k, npos) + 2))).sum()
    return dict(auc=auc, ndcg=dcg / idcg, p=rel[:k].mean(), top1=float(rel[0]),
                spread=float(np.percentile(scores, 90) - np.percentile(scores, 10)))

def zs(x): return (x - x.mean()) / (x.std() + 1e-9)

def run_user(liked_idx, heldout_idx, distract_idx, neg_pool_idx, texts, E, rng, neg_mult=10):
    """Rank held-out + distractors given `liked_idx` as the reader's likes."""
    k = len(liked_idx)
    negs = rng.sample(neg_pool_idx, min(len(neg_pool_idx), neg_mult * k))
    cands = heldout_idx + distract_idx
    is_pos = np.array([i in set(heldout_idx) for i in cands])
    train = liked_idx + negs; y = [0.95] * k + [0.0] * len(negs)
    res = {}
    # 1. The app as it stands: vocabulary from the training documents only.
    vec = Tfidf().fit([texts[i] for i in train])
    clf = LogReg(len(vec.terms_)).fit([vec.transform(texts[i]) for i in train], y)
    s_app = np.array([clf.predict(vec.transform(texts[i])) for i in cands])
    res["tfidf (app)"] = metrics(s_app, is_pos)
    # 2. Same model, vocabulary from everything on the device.
    vec2 = Tfidf().fit([texts[i] for i in train + cands])
    clf2 = LogReg(len(vec2.terms_)).fit([vec2.transform(texts[i]) for i in train], y)
    s_tf = np.array([clf2.predict(vec2.transform(texts[i])) for i in cands])
    res["tfidf (device vocab)"] = metrics(s_tf, is_pos)
    for name, emb in E.items():
        C = emb[cands]; L = emb[liked_idx]
        res[f"{name} centroid"] = metrics(C @ (L.mean(0) / np.linalg.norm(L.mean(0))), is_pos)
        s_knn = (C @ L.T).max(1)
        res[f"{name} nearest"] = metrics(s_knn, is_pos)
        f = dense_lr(emb[train], y)
        s_lr = f(C)
        res[f"{name} logreg"] = metrics(s_lr, is_pos)
        res[f"{name} + tfidf"] = metrics(zs(s_lr) + zs(s_tf), is_pos)
    return res

def summarise(all_res, label):
    rows = {}
    for r in all_res:
        for m, v in r.items():
            rows.setdefault(m, []).append(v)
    print(f"\n### {label}   ({len(all_res)} runs)")
    print(f"{'method':24} {'AUC':>12} {'nDCG@25':>12} {'P@25':>12} {'top card':>9} {'spread':>7}")
    ranked = sorted(rows.items(), key=lambda kv: -np.mean([x['ndcg'] for x in kv[1]]))
    for m, vs in ranked:
        a = np.array([x['auc'] for x in vs]); n = np.array([x['ndcg'] for x in vs])
        p = np.array([x['p'] for x in vs]); t = np.array([x['top1'] for x in vs])
        s = np.array([x['spread'] for x in vs])
        ci = lambda z: 1.96 * z.std(ddof=1) / math.sqrt(len(z))
        print(f"{m:24} {a.mean():.3f}±{ci(a):.3f} {n.mean():.3f}±{ci(n):.3f} "
              f"{p.mean():.3f}±{ci(p):.3f} {t.mean():9.2f} {s.mean():7.3f}")
    return rows

if __name__ == "__main__":
    corpus = pd.read_parquet(ROOT / "data" / "corpus_all.parquet")
    lib = json.load(open(ROOT / "data" / "library_resolved.json"))
    lib_ids = {e["arxiv_id"] for e in lib}
    rng0 = np.random.default_rng(0)
    field = corpus[corpus.primary.isin(["cs.CV", "cs.LG"]) & ~corpus.arxiv_id.isin(lib_ids)]
    pool = field.sample(4000, random_state=1)
    texts = [e["title"] + ". " + e["abstract"] for e in lib] + \
            [r.title + ". " + r.abstract for r in pool.itertuples()]
    L = list(range(len(lib))); P = list(range(len(lib), len(texts)))
    E = {}
    for m in MODELS:
        t0 = time.time(); E[m] = embed(m, texts)
        print(f"embedded {len(texts)} with {m} in {time.time()-t0:.0f}s", flush=True)
    for k in (3, 5, 10, 20):
        out = []
        for seed in range(25):
            rng = random.Random(seed)
            liked = rng.sample(L, k); held = [i for i in L if i not in liked]
            distract = rng.sample(P[:2000], 1500); negpool = P[2000:]
            out.append(run_user(liked, held, distract, negpool, texts, E, rng))
        summarise(out, f"real library, {k} liked papers, {len(held)} to find among 1500 cs.CV/cs.LG papers")

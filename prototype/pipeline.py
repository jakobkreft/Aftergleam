"""Faithful replica of the app's ranking pipeline, for measuring caption quality."""
import re, math, json, numpy as np, pandas as pd
from collections import Counter, defaultdict

STOP = set("""a an the and or of to in on for with is are was were be been by that this these those
we our it its as at from which can such also have has had but they their than then thus here""".split())

URL_RE = re.compile(r"https?://\S+|\bwww\.\S+|\b[a-z0-9-]+\.(com|org|io|net|github)\b")
LATEX_RE = re.compile(r"\\[a-zA-Z]+\*?")
MATH_RE = re.compile(r"\$[^$]*\$")
SPLIT_RE = re.compile(r"[^a-z0-9]+")

def clean(doc):
    d = doc.lower()
    d = URL_RE.sub(" ", d); d = MATH_RE.sub(" ", d); d = LATEX_RE.sub(" ", d)
    return d

def terms(doc):
    words = [w for w in SPLIT_RE.split(clean(doc))
             if len(w) > 2 and w not in STOP and not w.isdigit()]
    if len(words) < 2: return words
    return words + [words[i] + "_" + words[i+1] for i in range(len(words)-1)]

class Tfidf:
    def __init__(self, min_df=2, max_df_ratio=0.5, max_features=40000):
        self.min_df, self.max_df_ratio, self.max_features = min_df, max_df_ratio, max_features
    def fit(self, docs):
        df = Counter()
        for d in docs: df.update(set(terms(d)))
        max_df = max(int(len(docs) * self.max_df_ratio), self.min_df)
        kept = [(t, c) for t, c in df.items() if self.min_df <= c <= max_df]
        kept.sort(key=lambda tc: -tc[1])
        kept = kept[:self.max_features]
        self.vocab = {t: i for i, (t, c) in enumerate(kept)}
        self.terms_ = [t for t, c in kept]
        self.df_ = np.array([c for t, c in kept], dtype=np.float64)
        self.n_docs = len(docs)
        self.idf = np.log((1.0 + len(docs)) / (1.0 + self.df_)) + 1.0
        return self
    def transform(self, doc):
        counts = Counter()
        for t in terms(doc):
            i = self.vocab.get(t)
            if i is not None: counts[i] += 1
        if not counts: return np.array([], dtype=int), np.array([])
        idx = np.array(sorted(counts), dtype=int)
        vals = np.array([(1.0 + math.log(counts[i])) * self.idf[i] for i in idx])
        n = np.linalg.norm(vals)
        if n > 0: vals = vals / n
        return idx, vals

class LogReg:
    def __init__(self, dim, lr=0.5, l2=1e-4, epochs=200):
        self.dim, self.lr, self.l2, self.epochs = dim, lr, l2, epochs
    def fit(self, X, y):
        y = np.asarray(y, dtype=np.float64)
        pos = max(y.sum(), 1e-3); neg = max(len(y) - pos, 1e-3)
        wpos = len(y) / (2 * pos); wneg = len(y) / (2 * neg)
        self.w = np.zeros(self.dim); self.b = 0.0
        for _ in range(self.epochs):
            grad = np.zeros(self.dim); gb = 0.0
            for i, (idx, vals) in enumerate(X):
                p = self.predict((idx, vals))
                w = y[i] * wpos + (1 - y[i]) * wneg
                err = (p - y[i]) * w
                if len(idx): grad[idx] += err * vals
                gb += err
            scale = self.lr / len(X)
            self.w -= scale * grad + self.lr * self.l2 * self.w
            self.b -= scale * gb
        return self
    def predict(self, xv):
        idx, vals = xv
        z = self.b + (float(np.dot(self.w[idx], vals)) if len(idx) else 0.0)
        return 1.0 / (1.0 + math.exp(-z))
    def top_contributors(self, xv, n=3):
        idx, vals = xv
        if not len(idx): return []
        c = self.w[idx] * vals
        order = np.argsort(-np.abs(c))
        return [int(idx[k]) for k in order if c[k] > 0][:n]

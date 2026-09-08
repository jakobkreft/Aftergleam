"""E1 — do abstracts separate "interesting to me" from "not"?

This is the gate. If it fails, the project stops or pivots.

The metric is precision@10: hold out 20% of the user's library, mix it into 500 random
distractors from the same corpus, rank everything, count how many of the top 10 are real
positives. That mirrors the product — ten cards, once a day.

Two honesty rules, both easy to violate by accident:

  * **The narrow-category control is the real experiment.** Separating cs.LG from q-bio.NC
    is trivial and proves only that the model learned the category label. `--control`
    restricts positives AND distractors to one primary category, and that number is the
    one the pass bar refers to.

  * **Report the ceiling.** With N held-out positives, precision@10 cannot exceed
    min(10, N)/10. A "0.4" against a ceiling of 0.4 is a perfect score, and a "0.4"
    against a ceiling of 1.0 is a mediocre one. Never print one without the other.

Baselines run in cost order so the cheapest adequate model wins. If TF-IDF is within 15%
of embeddings, ship TF-IDF: no download, milliseconds, ~10 MB app.
"""

from __future__ import annotations

import numpy as np

RNG = 20260908


def _text(df):
    return (df.title.fillna("") + ". " + df.abstract.fillna("")).values


def precision_at_k(scores: np.ndarray, is_pos: np.ndarray, k: int = 10) -> float:
    order = np.argsort(-scores)
    return float(is_pos[order[:k]].sum()) / k


def evaluate(pos_df, corpus_df, method: str = "tfidf", n_distract: int = 500,
             test_frac: float = 0.2, n_seeds: int = 5, k: int = 10) -> dict:
    """Train on train-positives vs random negatives; rank held-out positives among distractors.

    Repeats over several seeds because with a few hundred positives a single split is noise.
    """
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression

    pos_txt = _text(pos_df)
    cor_txt = _text(corpus_df)
    scores, ceilings = [], []

    for seed in range(n_seeds):
        rng = np.random.default_rng(RNG + seed)
        idx = rng.permutation(len(pos_txt))
        n_test = max(1, int(len(idx) * test_frac))
        test_i, train_i = idx[:n_test], idx[n_test:]
        if len(train_i) < 5:
            raise ValueError(f"only {len(train_i)} training positives; need >= 5")

        # Easy negatives: random papers. Scholar Inbox does the same, and E6 will test
        # whether implicit negatives (skipped papers) collapse the feed instead.
        c_idx = rng.permutation(len(cor_txt))
        n_train_neg = min(len(train_i) * 5, len(c_idx) - n_distract)
        neg_i = c_idx[:n_train_neg]
        dis_i = c_idx[n_train_neg : n_train_neg + n_distract]

        train_txt = np.concatenate([pos_txt[train_i], cor_txt[neg_i]])
        train_y = np.concatenate([np.ones(len(train_i)), np.zeros(len(neg_i))])
        eval_txt = np.concatenate([pos_txt[test_i], cor_txt[dis_i]])
        eval_y = np.concatenate([np.ones(len(test_i)), np.zeros(len(dis_i))])

        if method == "random":
            s = np.random.default_rng(seed).random(len(eval_txt))
        elif method == "tfidf":
            vec = TfidfVectorizer(sublinear_tf=True, min_df=2, max_df=0.5,
                                  ngram_range=(1, 2), stop_words="english")
            Xtr = vec.fit_transform(train_txt)
            clf = LogisticRegression(max_iter=2000, class_weight="balanced", C=1.0)
            clf.fit(Xtr, train_y)
            s = clf.predict_proba(vec.transform(eval_txt))[:, 1]
        elif method == "centroid":
            vec = TfidfVectorizer(sublinear_tf=True, min_df=2, max_df=0.5, stop_words="english")
            vec.fit(train_txt)
            P = vec.transform(pos_txt[train_i])
            c = np.asarray(P.mean(axis=0)).ravel()
            c /= np.linalg.norm(c) + 1e-9
            E = vec.transform(eval_txt)
            E = E.multiply(1 / (np.sqrt(E.multiply(E).sum(1)) + 1e-9))
            s = np.asarray(E @ c).ravel()
        else:
            raise ValueError(f"unknown method {method!r}")

        scores.append(precision_at_k(s, eval_y, k))
        ceilings.append(min(k, len(test_i)) / k)

    return {
        "method": method,
        f"p@{k}": float(np.mean(scores)),
        "std": float(np.std(scores)),
        "ceiling": float(np.mean(ceilings)),
        "n_pos": len(pos_txt),
        "n_distract": n_distract,
    }


def report(pos_df, corpus_df, control_category: str | None = None, **kw) -> None:
    if control_category:
        pos_df = pos_df[pos_df["primary"] == control_category]
        corpus_df = corpus_df[corpus_df["primary"] == control_category]
        print(f"\n=== NARROW-CATEGORY CONTROL: {control_category} "
              f"({len(pos_df)} positives, {len(corpus_df)} corpus) ===")
    else:
        print(f"\n=== ALL CATEGORIES ({len(pos_df)} positives, {len(corpus_df)} corpus) ===")
        print("    (cross-field separation is easy — the control below is the real result)")

    rows = [evaluate(pos_df, corpus_df, m, **kw) for m in ("random", "centroid", "tfidf")]
    base = next(r for r in rows if r["method"] == "tfidf")
    print(f"\n  {'method':10s} {'p@10':>7s} {'±':>6s} {'ceiling':>8s}")
    for r in rows:
        print(f"  {r['method']:10s} {r['p@10']:7.3f} {r['std']:6.3f} {r['ceiling']:8.2f}")
    print(f"\n  TF-IDF is the bar embeddings must clear by 15%: "
          f"need p@10 >= {base['p@10'] * 1.15:.3f}")
    print(f"  E1 pass bar: p@10 >= 0.5 within a narrow category")


# --- leave-one-out ----------------------------------------------------------
# precision@10 with a 20% holdout is ceiling-limited when the library is small: with 24
# positives the holdout is 4-5 papers, so p@10 cannot exceed 0.4 and the "p@10 >= 0.5"
# pass bar is arithmetically unreachable. That is a property of the estimator, not of
# the model, and reporting it as a failure would be wrong.
#
# Leave-one-out removes the ceiling and asks the product question directly:
#   "this is a paper you actually read - would it have surfaced in your daily ten?"
# Each positive is held out in turn, ranked against `n_distract` random papers, and we
# report how often it lands in the top 10. Random baseline is 10/(n_distract+1) ~ 2%.


def evaluate_loo(pos_df, corpus_df, method: str = "tfidf", n_distract: int = 500,
                 k: int = 10, embedder=None, seed: int = RNG) -> dict:
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression

    pos_txt, cor_txt = _text(pos_df), _text(corpus_df)
    rng = np.random.default_rng(seed)
    n = len(pos_txt)

    if method.startswith("embed"):
        if embedder is None:
            raise ValueError(f"method={method!r} needs an embedder")
        P = embedder.encode(list(pos_txt))
        C = embedder.encode(list(cor_txt))

    ranks = []
    for i in range(n):
        train_i = np.array([j for j in range(n) if j != i])
        c_idx = rng.permutation(len(cor_txt))
        n_neg = min(len(train_i) * 10, len(c_idx) - n_distract)
        neg_i, dis_i = c_idx[:n_neg], c_idx[n_neg : n_neg + n_distract]

        if method == "tfidf":
            vec = TfidfVectorizer(sublinear_tf=True, min_df=2, max_df=0.5,
                                  ngram_range=(1, 2), stop_words="english")
            Xtr = vec.fit_transform(np.concatenate([pos_txt[train_i], cor_txt[neg_i]]))
            y = np.concatenate([np.ones(len(train_i)), np.zeros(len(neg_i))])
            clf = LogisticRegression(max_iter=2000, class_weight="balanced").fit(Xtr, y)
            s_eval = clf.predict_proba(
                vec.transform(np.concatenate([[pos_txt[i]], cor_txt[dis_i]])))[:, 1]
        elif method == "embed":
            X = np.vstack([P[train_i], C[neg_i]])
            y = np.concatenate([np.ones(len(train_i)), np.zeros(len(neg_i))])
            clf = LogisticRegression(max_iter=2000, class_weight="balanced").fit(X, y)
            s_eval = clf.predict_proba(np.vstack([P[i][None, :], C[dis_i]]))[:, 1]
        elif method == "embed_centroid":
            c = P[train_i].mean(0)
            c /= np.linalg.norm(c) + 1e-9
            s_eval = np.concatenate([[P[i] @ c], C[dis_i] @ c])
        elif method == "random":
            s_eval = rng.random(n_distract + 1)
        else:
            raise ValueError(method)

        # rank of the held-out positive (index 0) among all candidates, 1-based
        ranks.append(int((s_eval > s_eval[0]).sum()) + 1)

    ranks = np.array(ranks)
    return {
        "method": method,
        f"hit@{k}": float((ranks <= k).mean()),
        "hit@25": float((ranks <= 25).mean()),
        "median_rank": float(np.median(ranks)),
        "mrr": float((1 / ranks).mean()),
        "n_pos": n,
        "pool": n_distract + 1,
    }


def report_loo(pos_df, corpus_df, control_category=None, methods=("random", "tfidf"),
               embedder=None, n_distract: int = 500) -> None:
    label = "ALL CATEGORIES"
    if control_category:
        pos_df = pos_df[pos_df["primary"] == control_category]
        corpus_df = corpus_df[corpus_df["primary"] == control_category]
        label = f"NARROW-CATEGORY CONTROL: {control_category}"
    print(f"\n=== {label} (leave-one-out, {len(pos_df)} positives, "
          f"pool={n_distract + 1}) ===")
    print(f"  {'method':16s} {'hit@10':>7s} {'hit@25':>7s} {'med rank':>9s} {'MRR':>6s}")
    for m in methods:
        r = evaluate_loo(pos_df, corpus_df, m, n_distract=n_distract, embedder=embedder)
        print(f"  {r['method']:16s} {r['hit@10']:7.3f} {r['hit@25']:7.3f} "
              f"{r['median_rank']:9.0f} {r['mrr']:6.3f}")
    print(f"  {'(random floor)':16s} {10 / (n_distract + 1):7.3f} "
          f"{25 / (n_distract + 1):7.3f}")

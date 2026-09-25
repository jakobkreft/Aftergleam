"""ONNX Runtime sentence embedder — deliberately the same runtime as D3 targets.

Using onnxruntime rather than sentence-transformers/torch is not just about install size.
It means the ms/abstract numbers from E2 are measured on the runtime that will actually
ship, on CPU, with no GPU path silently helping. A torch benchmark would flatter us.

Mean-pooling over the last hidden state with attention masking, then L2 normalisation.
That is what all-MiniLM-L6-v2 and GTE expect. The bge family and Arctic do not: they use
the [CLS] token, so this class is only right for mean-pooled models. E1 used it for bge as
well, which is one reason E2 was rerun with each model pooled as its authors specify (see
e2.py). Getting the pooling wrong is a quiet way to lose several points of precision.
"""

from __future__ import annotations

import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

MODELS = Path(__file__).resolve().parent.parent / "models"


class Embedder:
    def __init__(self, model_dir: str = "minilm", quantized: bool = False,
                 max_len: int = 256, threads: int = 1):
        d = MODELS / model_dir
        name = "model_quantized.onnx" if quantized else "model.onnx"
        path = d / "onnx" / name
        if not path.exists():
            raise FileNotFoundError(path)

        so = ort.SessionOptions()
        # Single-threaded by default: a phone gives us roughly one big core for this,
        # and an 8-thread laptop number would not project honestly to the device.
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        self.sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
        self.inputs = {i.name for i in self.sess.get_inputs()}

        self.tok = Tokenizer.from_file(str(d / "tokenizer.json"))
        self.tok.enable_truncation(max_length=max_len)
        self.tok.enable_padding(length=None)
        self.size_mb = path.stat().st_size / 1e6
        self.name = f"{model_dir}{'-int8' if quantized else ''}"

    def encode(self, texts: list[str], batch: int = 32) -> np.ndarray:
        out = []
        for i in range(0, len(texts), batch):
            enc = self.tok.encode_batch(texts[i : i + batch])
            ids = np.array([e.ids for e in enc], dtype=np.int64)
            mask = np.array([e.attention_mask for e in enc], dtype=np.int64)
            feed = {"input_ids": ids, "attention_mask": mask}
            if "token_type_ids" in self.inputs:
                feed["token_type_ids"] = np.zeros_like(ids)
            hidden = self.sess.run(None, {k: v for k, v in feed.items() if k in self.inputs})[0]

            m = mask[..., None].astype(np.float32)
            pooled = (hidden * m).sum(1) / np.clip(m.sum(1), 1e-9, None)
            pooled /= np.linalg.norm(pooled, axis=1, keepdims=True) + 1e-9
            out.append(pooled.astype(np.float32))
        return np.vstack(out)

    def benchmark(self, texts: list[str], n: int = 64) -> dict:
        sample = texts[:n]
        self.encode(sample[:4])  # warm up; first run pays graph-init cost
        t0 = time.perf_counter()
        self.encode(sample)
        dt = time.perf_counter() - t0
        return {
            "model": self.name,
            "size_mb": round(self.size_mb, 1),
            "ms_per_abstract": round(dt / len(sample) * 1000, 1),
            "projected_300_on_phone_s": round(dt / len(sample) * 300 * 7, 1),
        }

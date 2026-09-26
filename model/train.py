"""Trains the intent classifier and writes it into the app's assets.

    python3 train.py

Output:
  ../app/src/main/assets/nlu_model.bin   little-endian: int32 DIM, int32 C,
                                          float32 W[DIM*C] (row = feature), float32 b[C]
  ../app/src/main/assets/nlu_labels.txt  one label per line, in column order
"""
import os
import struct
import sys

import numpy as np
from scipy import sparse

from features import DIM, featurize
from generate_data import INTENTS, generate

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")


def to_matrix(texts):
    rows, cols, vals = [], [], []
    for i, t in enumerate(texts):
        for k, v in featurize(t):
            rows.append(i)
            cols.append(k)
            vals.append(v)
    return sparse.csr_matrix((vals, (rows, cols)), shape=(len(texts), DIM), dtype=np.float32)


def softmax(z):
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def train(X, y, C, epochs=40, lr=0.05, l2=1e-5, batch=256, seed=0):
    rng = np.random.default_rng(seed)
    W = np.zeros((DIM, C), dtype=np.float32)
    b = np.zeros(C, dtype=np.float32)
    mW, vW = np.zeros_like(W), np.zeros_like(W)
    mb, vb = np.zeros_like(b), np.zeros_like(b)
    b1, b2, eps = 0.9, 0.999, 1e-8
    t = 0
    n = X.shape[0]
    for ep in range(epochs):
        idx = rng.permutation(n)
        for s in range(0, n, batch):
            bi = idx[s:s + batch]
            xb = X[bi]
            p = softmax(xb @ W + b)
            p[np.arange(len(bi)), y[bi]] -= 1
            p /= len(bi)
            gW = (xb.T @ p) + l2 * W
            gb = p.sum(axis=0)
            t += 1
            mW = b1 * mW + (1 - b1) * gW
            vW = b2 * vW + (1 - b2) * gW * gW
            mb = b1 * mb + (1 - b1) * gb
            vb = b2 * vb + (1 - b2) * gb * gb
            W -= lr * (mW / (1 - b1 ** t)) / (np.sqrt(vW / (1 - b2 ** t)) + eps)
            b -= lr * (mb / (1 - b1 ** t)) / (np.sqrt(vb / (1 - b2 ** t)) + eps)
        acc = (np.asarray(X @ W + b).argmax(axis=1) == y).mean()
        print(f"epoch {ep + 1}: train acc {acc:.4f}", file=sys.stderr)
    return W, b


def main():
    labels = list(INTENTS.keys())
    data = generate()
    texts = [d[0] for d in data]
    y = np.array([labels.index(d[1]) for d in data])
    print(f"{len(texts)} training sentences, {len(labels)} intents", file=sys.stderr)
    X = to_matrix(texts)
    W, b = train(X, y, len(labels), epochs=int(os.environ.get("EPOCHS", 25)))

    tests = []
    with open(os.path.join(HERE, "test_phrases.tsv"), encoding="utf-8") as f:
        for line in f:
            if line.strip():
                lab, txt = line.rstrip("\n").split("\t")
                tests.append((txt, lab))
    P = softmax(np.asarray(to_matrix([t[0] for t in tests]) @ W + b))
    ok = 0
    for (txt, lab), p in zip(tests, P):
        pred = labels[p.argmax()]
        if pred == lab:
            ok += 1
        else:
            print(f"  MISS  {txt!r}: expected {lab}, got {pred} ({p.max():.2f})")
    print(f"held-out test accuracy: {ok}/{len(tests)} = {ok / len(tests):.3f}")

    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "nlu_model.bin"), "wb") as f:
        f.write(struct.pack("<ii", DIM, len(labels)))
        f.write(W.astype("<f4").tobytes())
        f.write(b.astype("<f4").tobytes())
    with open(os.path.join(ASSETS, "nlu_labels.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(labels) + "\n")
    # Reference predictions used by the Kotlin parity test.
    with open(os.path.join(HERE, "reference_predictions.tsv"), "w", encoding="utf-8") as f:
        for (txt, _), p in zip(tests, P):
            f.write(f"{txt}\t{labels[p.argmax()]}\t{p.max():.5f}\n")


if __name__ == "__main__":
    main()

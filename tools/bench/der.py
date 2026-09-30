"""DER (ошибка диаризации) по кадрам 10 мс, коллар 0.25 с, с перекрытиями.
Без внешних зависимостей: сопоставление говорящих — перебор (эталон ≤ 8 говорящих)."""
import itertools
import numpy as np

STEP = 0.01


def load_rttm(path, t_max=None):
    segs = []
    for line in open(path):
        p = line.split()
        s, d, spk = float(p[3]), float(p[4]), p[7]
        if t_max is None or s < t_max:
            segs.append((s, min(s + d, t_max or 1e9), spk))
    return segs


def _matrix(segs, n):
    names = sorted({x[2] for x in segs})
    m = np.zeros((len(names), n), dtype=bool)
    for s, e, spk in segs:
        m[names.index(spk), int(s / STEP):int(e / STEP)] = True
    return m


def der(ref, hyp, dur, collar=0.25):
    n = int(dur / STEP) + 1
    R, H = _matrix(ref, n), _matrix(hyp, n)
    mask = np.ones(n, dtype=bool)
    c = int(collar / STEP)
    for s, e, _ in ref:
        for t in (s, e):
            mask[max(0, int(t / STEP) - c):int(t / STEP) + c] = False
    R, H = R[:, mask], H[:, mask]
    total = R.sum()
    nr, nh = R.sum(0), H.sum(0)
    miss = np.maximum(nr - nh, 0).sum()
    fa = np.maximum(nh - nr, 0).sum()
    # лучшее сопоставление говорящих: максимум совпавших кадров
    ov = (R[:, None, :] & H[None, :, :]).sum(2)
    k = min(len(R), len(H))
    best = 0
    if len(R) <= len(H):
        for perm in itertools.permutations(range(len(H)), len(R)):
            best = max(best, sum(ov[i, perm[i]] for i in range(len(R))))
    else:
        for perm in itertools.permutations(range(len(R)), len(H)):
            best = max(best, sum(ov[perm[j], j] for j in range(len(H))))
    correct = best
    conf = np.minimum(nr, nh).sum() - correct
    return dict(der=(miss + fa + conf) / total, miss=miss / total, fa=fa / total, conf=conf / total,
                n_hyp=len(H), n_ref=len(R))

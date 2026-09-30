"""Скорость и качество диаризации sherpa-onnx на AMI (тот же C++-движок, что в APK).
Запуск: venv/bin/python diar_bench.py <m-dir> <ami-dir> <конфиг>... ; конфиг = emb,shift,thr,threads[,N]"""
import subprocess, sys, time, os
import numpy as np, sherpa_onnx as so
sys.path.insert(0, os.path.dirname(__file__))
from der import der, load_rttm

M, AMI = sys.argv[1], sys.argv[2]
EMB = {"titanet": "nemo_en_titanet_small.onnx", "titanet8": "titanet_small.int8.onnx", "wespeaker": "wespeaker_en_voxceleb_resnet34_LM.onnx",
       "eres2net": "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"}


def load(p):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", p, "-ac", "1", "-ar", "16000", "-f", "f32le", "-"],
                         capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


files = [(n, load(f"{AMI}/{n}.wav"), load_rttm(f"{AMI}/{n}.rttm")) for n in ("ES2004a", "IS1009a")]
for spec in sys.argv[3:]:
    p = spec.split(",")
    emb, shift, thr, th = p[0], float(p[1]), float(p[2]), int(p[3])
    n = int(p[4]) if len(p) > 4 else -1
    c = so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(
            pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=f"{M}/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx"), num_threads=th),
        embedding=so.SpeakerEmbeddingExtractorConfig(model=f"{M}/{EMB[emb]}", num_threads=th),
        clustering=so.FastClusteringConfig(num_clusters=n, threshold=thr),
        min_duration_on=0.3, min_duration_off=0.5)
    c.segmentation.pyannote.window_shift_ratio = shift
    d = so.OfflineSpeakerDiarization(c)
    for name, a, ref in files:
        t = time.time()
        r = d.process(a).sort_by_start_time()
        el = time.time() - t
        hyp = [(s.start, s.end, str(s.speaker)) for s in r]
        m = der(ref, hyp, len(a) / 16000)
        print(f"{spec:24s} {name} {el:6.1f}s  RTF {el / (len(a) / 16000):.3f}  DER {m['der']:.3f} "
              f"(miss {m['miss']:.3f} fa {m['fa']:.3f} conf {m['conf']:.3f}) spk {m['n_hyp']}/{m['n_ref']}", flush=True)

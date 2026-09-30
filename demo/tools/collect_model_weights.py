"""Collect model files from the current machine into demo/models.

Usage:
    python demo/tools/collect_model_weights.py

The script copies only weights already present locally. It does not download
anything and writes model_manifest.json with sizes and SHA-256 hashes.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
MODEL_ROOT = Path(os.getenv("PHOTO_SCREENER_MODEL_ROOT", str(PROJECT_ROOT / "models"))).resolve()


def copy_file(source: Path, target: Path, role: str, manifest: list[dict]) -> None:
    if not source.is_file():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.resolve() != target.resolve():
        shutil.copy2(source, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    manifest.append({"role": role, "path": target.relative_to(MODEL_ROOT).as_posix(), "size": target.stat().st_size, "sha256": digest})
    print(f"copied {role}: {source} -> {target}")


def main() -> int:
    manifest: list[dict] = []

    # DeepFace looks below DEEPFACE_HOME/.deepface/weights.
    deepface_source = Path(os.getenv("DEEPFACE_HOME", str(Path.home()))) / ".deepface" / "weights"
    deepface_target = MODEL_ROOT / "deepface_home" / ".deepface" / "weights"
    for path in sorted(deepface_source.glob("*")):
        if path.suffix.lower() in {".pth", ".h5", ".onnx", ".bin"}:
            copy_file(path, deepface_target / path.name, "deepface", manifest)

    clip_source = Path(os.getenv("CHINESE_CLIP_CHECKPOINT", str(Path.home() / ".cache" / "clip" / "clip_cn_vit-b-16.pt")))
    copy_file(clip_source, MODEL_ROOT / "chinese_clip" / "clip_cn_vit-b-16.pt", "chinese_clip", manifest)

    # Materialize the DINOv2 snapshot as regular files instead of carrying
    # Hugging Face's snapshot symlinks across operating systems/servers.
    hf_home = Path(os.getenv("HF_HOME", str(MODEL_ROOT / "huggingface")))
    dino_cache = hf_home / "hub" / "models--facebook--dinov2-small" / "snapshots"
    snapshots = sorted((path for path in dino_cache.iterdir() if path.is_dir()), key=lambda path: path.stat().st_mtime) if dino_cache.exists() else []
    if snapshots:
        snapshot = snapshots[-1]
        for source in snapshot.rglob("*"):
            if source.is_file():
                copy_file(source, MODEL_ROOT / "dinov2-small" / source.relative_to(snapshot), "dinov2", manifest)

    # MediaPipe's legacy solutions load these package-bundled assets. Copying
    # them is useful for inventory, but the runtime still needs mediapipe
    # installed because it also contains native bindings and graph files.
    mp_spec = importlib.util.find_spec("mediapipe")
    if mp_spec and mp_spec.submodule_search_locations:
        mp_root = Path(next(iter(mp_spec.submodule_search_locations)))
        for relative in (
            "modules/face_detection/face_detection_short_range.tflite",
            "modules/face_detection/face_detection_full_range_sparse.tflite",
            "modules/face_landmark/face_landmark.tflite",
            "modules/face_landmark/face_landmark_with_attention.tflite",
            "modules/pose_detection/pose_detection.tflite",
            "modules/pose_landmark/pose_landmark_full.tflite",
            "modules/palm_detection/palm_detection_full.tflite",
            "modules/hand_landmark/hand_landmark_full.tflite",
        ):
            source = mp_root / relative
            copy_file(source, MODEL_ROOT / "mediapipe" / Path(relative).name, "mediapipe", manifest)
    else:
        print("mediapipe is not installed; skipped its bundled assets", file=sys.stderr)

    # Include any already-materialized HF model files and configuration assets
    # in the manifest too (without copying its internal cache structure).

    MODEL_ROOT.mkdir(parents=True, exist_ok=True)
    # Keep the manifest portable when it is committed alongside the source.
    (MODEL_ROOT / "model_manifest.json").write_text(json.dumps({"model_root": ".", "files": manifest}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"manifest: {MODEL_ROOT / 'model_manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

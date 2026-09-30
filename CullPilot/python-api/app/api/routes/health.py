from importlib import metadata, util

from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict[str, str]:
    return {
        "service": "cullpilot-python-api",
        "status": "ok",
    }


@router.get("/health/models")
async def model_health() -> dict:
    from app.config import settings
    from demo.agent import chinese_clip_encoder, face_analysis, pose_analysis, semantic_encoder
    from demo.agent.model_paths import (
        CHINESE_CLIP_DEFAULT_CHECKPOINT,
        DEEPFACE_HOME,
        DINO_MODEL_DIR,
        MODEL_ROOT,
    )

    required_files = {
        "deepface": [
            DEEPFACE_HOME / ".deepface" / "weights" / name
            for name in (
                "age_model_weights.pth",
                "facenet_weights.pth",
                "facial_expression_model_weights.pth",
                "gender_model_weights.pth",
                "vgg_face_weights.pth",
            )
        ],
        "dinov2": [
            DINO_MODEL_DIR / "config.json",
            DINO_MODEL_DIR / "preprocessor_config.json",
            DINO_MODEL_DIR / "model.safetensors",
        ],
        "mediapipe": [
            MODEL_ROOT / "mediapipe" / name
            for name in (
                "face_detection_short_range.tflite",
                "face_detection_full_range_sparse.tflite",
                "face_landmark.tflite",
                "face_landmark_with_attention.tflite",
                "pose_detection.tflite",
                "pose_landmark_full.tflite",
                "palm_detection_full.tflite",
                "hand_landmark_full.tflite",
            )
        ],
        "chinese_clip": [CHINESE_CLIP_DEFAULT_CHECKPOINT],
    }
    inventory = {
        role: {
            "present": sum(path.is_file() for path in paths),
            "total": len(paths),
            "missing": [str(path.relative_to(MODEL_ROOT)) for path in paths if not path.is_file()],
        }
        for role, paths in required_files.items()
    }

    try:
        mediapipe_version = metadata.version("mediapipe")
    except metadata.PackageNotFoundError:
        mediapipe_version = None

    mediapipe_ready = face_analysis.mp is not None and getattr(pose_analysis, "mp", None) is not None
    deepface_ready = face_analysis._DEEPFACE_AVAILABLE and not inventory["deepface"]["missing"]
    dino_dependencies_ready = util.find_spec("torch") is not None and util.find_spec("transformers") is not None
    dino_ready = dino_dependencies_ready and not inventory["dinov2"]["missing"]
    chinese_clip_dependencies_ready = util.find_spec("torch") is not None and util.find_spec("cn_clip") is not None
    chinese_clip_ready = chinese_clip_dependencies_ready and not inventory["chinese_clip"]["missing"]
    active_checks = []
    if settings.analysis_enable_deepface:
        active_checks.append(deepface_ready)
    if settings.analysis_enable_semantic:
        active_checks.append(dino_ready)
    if settings.analysis_enable_chinese_clip:
        active_checks.append(chinese_clip_ready)
    active_checks.append(mediapipe_ready)
    dino_status = semantic_encoder.status()
    chinese_clip_status = chinese_clip_encoder.status()

    return {
        "service": "cullpilot-python-api",
        "status": "ok" if all(active_checks) else "degraded",
        "model_root": str(MODEL_ROOT),
        "inventory": inventory,
        "components": {
            "mediapipe": {
                "enabled": True,
                "ready": mediapipe_ready,
                "version": mediapipe_version,
                "legacy_solutions_api": mediapipe_ready,
                "bundled_model_files_present": inventory["mediapipe"]["present"],
                "bundled_model_files_total": inventory["mediapipe"]["total"],
                "error": getattr(face_analysis, "_MEDIAPIPE_ERROR", None),
            },
            "deepface": {
                "enabled": settings.analysis_enable_deepface,
                "ready": deepface_ready if settings.analysis_enable_deepface else None,
                "imported": face_analysis._DEEPFACE_AVAILABLE,
                "weights_present": not inventory["deepface"]["missing"],
            },
            "dinov2": {
                "enabled": settings.analysis_enable_semantic,
                "ready": dino_ready if settings.analysis_enable_semantic else None,
                "dependencies_installed": dino_dependencies_ready,
                "weights_present": not inventory["dinov2"]["missing"],
                "loaded": dino_status["available"],
                "error": dino_status["error"],
            },
            "chinese_clip": {
                "enabled": settings.analysis_enable_chinese_clip,
                "ready": chinese_clip_ready if settings.analysis_enable_chinese_clip else None,
                "dependencies_installed": chinese_clip_dependencies_ready,
                "weights_present": not inventory["chinese_clip"]["missing"],
                "loaded": chinese_clip_status["available"],
                "error": chinese_clip_status["error"],
                "note": "Used as an optional image-semantic signal when enabled",
            },
        },
    }

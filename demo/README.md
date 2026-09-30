# CullPilot Algorithm Module

This directory contains the canonical image-analysis implementation used by
the CullPilot Python API.

## Runtime boundary

CullPilot imports the algorithm package from `demo/agent`:

```text
CullPilot/backend-java
    -> CullPilot/python-api
    -> demo/agent
```

The Java service owns business workflows, users, projects, storage, jobs,
review decisions, and exports. The Python service adapts the internal request
contract and calls the algorithms in this directory.

## Kept contents

- `agent/`: feature extraction, face and pose analysis, grouping, scoring,
  recommendation, and optional semantic encoders.
- `tests/`: algorithm-level regression tests.
- `tools/`: utilities for collecting model weights from an existing machine.
- `.env.example`: optional local algorithm configuration.

The old Flask server, HTML pages, upload workflow, Docker files, and standalone
Demo API have been removed. They are not part of the CullPilot runtime.

## Install

Install dependencies from the CullPilot Python service:

```powershell
cd CullPilot/python-api
python -m pip install -r requirements.txt
```

For MediaPipe face and pose analysis:

```powershell
python -m pip install -r requirements-ai.txt
```

DeepFace, DINOv2, and Chinese-CLIP are optional. They are enabled explicitly
through the CullPilot environment settings:

```env
ANALYSIS_ENABLE_DEEPFACE=true
ANALYSIS_ENABLE_SEMANTIC=true
ANALYSIS_ENABLE_CHINESE_CLIP=false
ANALYSIS_MODEL_ROOT=
```

To enable Chinese-CLIP, install the official `cn-clip` package and set
`ANALYSIS_ENABLE_CHINESE_CLIP=true`. On Windows/Python 3.12, the package's
old `lmdb==1.3.0` pin has no compatible wheel, so install the compatible
runtime dependency and the package without dependency resolution:

```powershell
python -m pip install "lmdb>=2.3" "timm>=1.0"
python -m pip install --no-deps cn-clip==1.6.0
```

On Linux, `python -m pip install cn-clip` is sufficient. The prepared
`models/chinese_clip/clip_cn_vit-b-16.pt` checkpoint is loaded locally; no
model download is needed at runtime when offline mode is enabled.

The algorithm package can run without those optional models. It returns
warnings and uses the available feature-based scoring path when an optional
model is disabled or unavailable. CullPilot's example configuration enables
DeepFace and DINOv2, so the prepared model directory should be present when
running the full portrait-analysis workflow.

## Model layout

When model files are available, keep them under `demo/models/` or set
`ANALYSIS_MODEL_ROOT` to another directory. The model path is shared by the
algorithm package and the CullPilot Python service.

## Model manifest

The offline model directory can be laid out as follows:

| Component | Relative path under `demo/models/` | Size |
| --- | --- | ---: |
| DeepFace age | `deepface_home/.deepface/weights/age_model_weights.pth` | 514 MB |
| DeepFace face recognition | `deepface_home/.deepface/weights/facenet_weights.pth` | 87 MB |
| DeepFace emotion | `deepface_home/.deepface/weights/facial_expression_model_weights.pth` | 6 MB |
| DeepFace gender | `deepface_home/.deepface/weights/gender_model_weights.pth` | 512 MB |
| DeepFace VGG-Face | `deepface_home/.deepface/weights/vgg_face_weights.pth` | 553 MB |
| Chinese-CLIP | `chinese_clip/clip_cn_vit-b-16.pt` | 718 MB |
| DINOv2-small | `dinov2-small/model.safetensors` | 84 MB |
| DINOv2 config | `dinov2-small/config.json`, `preprocessor_config.json` | small |
| MediaPipe assets | `mediapipe/*.tflite` | 21 MB |

The corresponding feature switches are:

```env
ANALYSIS_ENABLE_DEEPFACE=true
ANALYSIS_ENABLE_SEMANTIC=true
```

DeepFace weights are needed only when `ANALYSIS_ENABLE_DEEPFACE=true`.
DINOv2 weights are needed only when `ANALYSIS_ENABLE_SEMANTIC=true`.
Chinese-CLIP weights are needed only when
`ANALYSIS_ENABLE_CHINESE_CLIP=true`. CullPilot uses it as an optional image
semantic signal; it does not replace the rule-based instruction parser.

MediaPipe still needs to be installed through
`CullPilot/python-api/requirements-ai.txt`; copying its `.tflite` files alone
does not install the Python runtime package.

"""定位 DeepFace WinError 6 的精确原因"""
import sys, os
os.environ["DEEPFACE_BACKEND_ENGINE"] = "pytorch"
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import cv2, numpy as np
from deepface import DeepFace

d = "data/sample_photos"
img_path = os.path.join(d, "mmexport1721303246879.jpg")
img = cv2.imdecode(np.fromfile(img_path, dtype=np.uint8), cv2.IMREAD_COLOR)
rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

# 裁切第一张人脸
import mediapipe as mp
mp_face = mp.solutions.face_detection
with mp_face.FaceDetection(model_selection=0, min_detection_confidence=0.3) as detector:
    result = detector.process(rgb)
    det = result.detections[0]
    bbox = det.location_data.relative_bounding_box
    x = int(bbox.xmin * img.shape[1])
    y = int(bbox.ymin * img.shape[0])
    w = int(bbox.width * img.shape[1])
    h = int(bbox.height * img.shape[0])
    # 加30%边距
    margin_x, margin_y = int(w * 0.3), int(h * 0.3)
    x1, y1 = max(0, x - margin_x), max(0, y - margin_y)
    x2, y2 = min(img.shape[1], x + w + margin_x), min(img.shape[0], y + h + margin_y)

face_rgb = rgb[y1:y2, x1:x2]
print(f"人脸裁切尺寸: {face_rgb.shape}")

# 测试1: 直接传 numpy 数组
print("\n测试1: DeepFace.analyze (numpy 数组)")
try:
    r = DeepFace.analyze(img_path=face_rgb, actions=["emotion"], detector_backend="skip", enforce_detection=False)
    print(f"  OK: {r}")
except Exception as e:
    print(f"  失败: {type(e).__name__}: {e}")

# 测试2: 保存到临时文件后传路径
print("\n测试2: DeepFace.analyze (临时文件)")
import tempfile
tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
success, buf = cv2.imencode(".jpg", cv2.cvtColor(face_rgb, cv2.COLOR_RGB2BGR))
tmp.write(buf.tobytes())
tmp.close()
try:
    r = DeepFace.analyze(img_path=tmp.name, actions=["emotion"], detector_backend="skip", enforce_detection=False)
    print(f"  OK: {r}")
except Exception as e:
    print(f"  失败: {type(e).__name__}: {e}")
finally:
    os.unlink(tmp.name)

# 测试3: 保存到 data/ 目录下传路径
print("\n测试3: DeepFace.analyze (data/ 目录)")
local_path = os.path.join(d, "_temp_face.jpg")
cv2.imwrite(local_path, cv2.cvtColor(face_rgb, cv2.COLOR_RGB2BGR))
try:
    r = DeepFace.analyze(img_path=local_path, actions=["emotion"], detector_backend="skip", enforce_detection=False)
    print(f"  OK: {r}")
except Exception as e:
    print(f"  失败: {type(e).__name__}: {e}")
finally:
    if os.path.exists(local_path):
        os.unlink(local_path)

# 测试4: DeepFace.represent (确认能工作)
print("\n测试4: DeepFace.represent (numpy 数组)")
try:
    r = DeepFace.represent(img_path=face_rgb, detector_backend="skip", enforce_detection=False)
    if r and len(r) > 0:
        emb = r[0].get("embedding", [])
        print(f"  OK: embedding 维度={len(emb)}")
except Exception as e:
    print(f"  失败: {type(e).__name__}: {e}")
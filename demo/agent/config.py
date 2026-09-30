""" 
算法参数集中配置
所有阈值、权重等可调参数统一管理，方便后期调参
"""

import os

# ============================================================
# 图片加载
# ============================================================
SUPPORTED_FORMATS = {".jpg", ".jpeg", ".png", ".webp"}
THUMBNAIL_SIZE = (320, 320)          # 缩略图尺寸
ANALYSIS_SIZE = (1024, 1024)         # 分析用图最大尺寸

# ============================================================
# 模块1: 特征提取参数
# ============================================================
BRIGHTNESS_WELL_EXPOSED = (0.25, 0.85)   # 曝光良好的亮度范围
HIGHLIGHT_THRESHOLD = 250                # 过曝阈值 (0~255)
SHADOW_THRESHOLD = 10                    # 欠曝阈值 (0~255)
LAPLACIAN_KSIZE = 3                      # Laplacian 核大小
SHARPNESS_NORMALIZE_MAX = 500.0          # Laplacian 方差归一化上限

# ============================================================
# 模块2: 分组参数
# ============================================================
TIME_WINDOW_SECONDS = 600                # 时间窗口 10 分钟
SLIDING_WINDOW_SIZE = 10                 # 前后各取多少张
CLUSTER_THRESHOLD = 0.48                 # 平均链接距离阈值 (越低分组越严格)
CLUSTER_MIN_FACE_CONFIDENCE = 0.30
FACE_NMS_IOU_THRESHOLD = 0.45
FACE_MIN_SIZE_RATIO = 0.0025          # 允许约 0.25% 画面面积的小脸候选
FACE_DETECTION_MAX_SIDE = 2400        # 多尺度检测的最大边，避免超大图爆内存
FACE_DETECTION_TILE_OVERLAP = 0.22    # 大图分块检测的重叠比例
FACE_MATCH_MIN_SIMILARITY = 0.78     # 人脸身份/组合相似度最低门槛
FACE_CONTEXT_MIN_SIMILARITY = 0.58   # 人像图片整体背景/场景相似度最低门槛

# 有人 / 无人照片的不同相似度权重
FACE_SIMILARITY_WEIGHTS = {
    "phash": 0.15,
    "color": 0.15,
    "time": 0.10,
    "face_embedding": 0.40,
    "face_count": 0.10,
    "position": 0.10,
}
NO_FACE_SIMILARITY_WEIGHTS = {
    "phash": 0.35,
    "color": 0.30,
    "texture": 0.20,
    "time": 0.15,
}

# ============================================================
# 模块4: 推荐参数
# ============================================================
MMR_LAMBDA = 0.65                        # MMR 多样性系数 (越高越重视质量)

# 三种严格度对应的阈值 (keep_threshold, discard_threshold)
STRICTNESS_THRESHOLDS = {
    "loose":    (0.45, 0.20),
    "standard": (0.55, 0.30),
    "strict":   (0.65, 0.40),
}

DEFAULT_STRICTNESS = "standard"
DEFAULT_KEEP_COUNT = 2

# ============================================================
# 模块3: 人脸
# ============================================================
EAR_CLOSED_THRESHOLD = 0.20              # 眼睛开合度低于此值判为闭眼
FRONTAL_YAW_THRESHOLD = 30               # 正面判断的最大 yaw 角度
# FACE_MIN_SIZE_RATIO is defined with the detector parameters above.
FACE_COMPLETENESS_MARGIN = 0.05          # 人脸框距边缘多少算"不完整"

# ============================================================
# 模块5: LLM
# ============================================================
LLM_MODEL = os.getenv("LLM_MODEL", "DeepSeek-Flash")
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://www.sophnet.com/api/open-apis/v1")
LLM_TIMEOUT = int(os.getenv("LLM_TIMEOUT", "15"))
LLM_MAX_RETRIES = int(os.getenv("LLM_MAX_RETRIES", "2"))
LLM_TEMPERATURE = float(os.getenv("LLM_TEMPERATURE", "0.1"))

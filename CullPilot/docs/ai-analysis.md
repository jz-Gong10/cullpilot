# AI 图片筛选适配说明

## 运行流程

1. 前端只调用 Spring Boot：`POST /api/v1/projects/{projectId}/analyze`，请求体可传 `{"force":false,"rebuildGroups":true}`。
2. Spring Boot 从数据库读取该项目的所有图片与项目设置，向 Python 的 `POST /api/v1/internal/analyze` 一次发送整个项目。因为分组和组内排序都需要比较多张图，不能逐张调用。
3. Python 读取双方共用的 `STORAGE_ROOT` 下的原图，依次执行 demo 中的基础特征、人脸和姿态检测、相似聚类、质量评分、MMR 组内推荐。返回分组、每张图片的 AI 推荐、分数、排名和原因。
4. Spring Boot 校验所有图片恰好属于一个分组，原子写入 `asset_groups` 和 `assets` 的分析字段，并完成任务。前端查询任务、分组和组内图片即可展示结果。
5. 算法只更新 `recommendation`，不会修改 `decision` 或 `decision_history`。导出时仍按已有规则优先采用用户决定。

## 用户筛选要求和默认策略

- `POST /api/v1/projects/{projectId}/parse-instruction` 接收用户自然语言要求，只返回策略预览，不自动修改设置。
- 前端确认预览后，调用 `PATCH /api/v1/projects/{projectId}/settings` 保存 `keepPerGroup`、`strictness`、`contentMode`、`weights` 和 `constraints`，再启动分析。
- 用户没有输入要求，或项目权重仍是默认值时，分析会按实际图片类型选择默认策略：人脸分组使用人像权重，无人脸分组使用风景权重。混合项目会分别处理。
- 当前要求解析使用 Python 内置规则解析器；Chinese-CLIP 适配器仍是可选的语义模型，不是用户要求输入接口的必需依赖。

## 接口和字段

- `GET /api/v1/projects/{projectId}/groups?page=1&pageSize=50`：分页查询分组，包含相似度、原因、AI 推荐保留的图片 ID。
- `GET /api/v1/groups/{groupId}/assets?page=1&pageSize=50`：按 `rank` 升序查询组内图片，包含 `recommendation`、`recommendScore`、`recommendReasons`、`membershipReason`、`quality`、`features` 和用户的 `decision`。
- `GET /api/v1/assets/{assetId}`：查询单张图及上述分析字段。
- `rebuildGroups=false`：保留已有分组成员，给新图片单独分组并重算所有图片排名；`rebuildGroups=true` 重新聚类。若 `force=false`、不重建且所有图片已完成分析，任务直接完成。

Python 内部接口使用 snake_case。`project_id` 和 `asset_id` 是数据库 UUID，`file_path` 是相对 `STORAGE_ROOT` 的路径。Python 只允许访问该项目、该图片目录中的 `original.*` 文件，不直接访问业务数据库。浏览器不能直接调用内部接口。

## 环境配置

从项目根目录分别启动，两个服务使用同一个绝对存储目录。例如 PowerShell：

```powershell
$env:STORAGE_ROOT="D:\PythonFile\CullPilot\storage"
cd python-api
python -m pip install -r requirements-ai.txt
python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

另开终端：

```powershell
$env:STORAGE_ROOT="D:\PythonFile\CullPilot\storage"
cd backend-java
mvn spring-boot:run
```

默认安装 `requirements-ai.txt`，该文件包含基础依赖，并固定使用仍提供 `mp.solutions` 接口的 `mediapipe==0.10.21`。若环境装成 0.10.35 等不兼容版本，人脸检测、面部特征和姿态分析会降级；本地复制的 `.tflite` 文件不能替代 MediaPipe Python 包及其兼容 API。DeepFace 和 DINOv2 在 CullPilot 的示例配置中默认启用，并从 `ANALYSIS_MODEL_ROOT` 指向的离线模型目录加载；缺少对应模型或依赖时会降级到基础特征分组和评分。Chinese-CLIP 默认关闭，但可通过 `ANALYSIS_ENABLE_CHINESE_CLIP=true` 启用，作为额外的图像语义相似度信号参与分组。

启用 Chinese-CLIP 前安装官方 Python 包：

```powershell
python -m pip install "lmdb>=2.3" "timm>=1.0"
python -m pip install --no-deps cn-clip==1.6.0
```

这是 Windows/Python 3.12 的安装方式：`cn-clip 1.6.0` 固定依赖旧版
`lmdb==1.3.0`，该版本没有可用的 CPython 3.12 Windows wheel。Linux 环境可直接执行
`python -m pip install cn-clip`。

如果当前镜像找不到该包，可从官方源码安装：

```powershell
git clone https://github.com/OFA-Sys/Chinese-CLIP.git
cd Chinese-CLIP
python -m pip install -e .
```

然后在 Python API 的 `.env` 中设置 `ANALYSIS_ENABLE_CHINESE_CLIP=true`，并确保
`ANALYSIS_MODEL_ROOT` 指向包含 `chinese_clip/clip_cn_vit-b-16.pt` 的目录。由于服务设置了
离线模式，启动时会使用这个本地权重，不会在线下载。检查
`GET /api/v1/health/models` 中 `components.chinese_clip` 的 `dependencies_installed`、
`weights_present` 和 `loaded` 字段。

启动后可访问 `GET /api/v1/health/models`，检查模型目录、文件清单、DeepFace/DINOv2 依赖和 MediaPipe API 兼容状态。该接口不会为模型做哈希校验或强制加载尚未使用的惰性模型。

Python API 使用仓库根目录下的 `demo/agent` 作为算法包，部署时只需要把 `demo/agent` 与 `CullPilot` 一起保留；旧的 Demo 页面和 Flask 服务不属于运行时依赖。

内部调用可能需要处理 1000 张图片；默认 `PYTHON_API_TIMEOUT_SECONDS=600`。模型和图片规模较大时应按实际耗时调整。Python 内部 API 默认只监听 `127.0.0.1`，跨主机部署需配合内网访问控制。上传和删除项目在分析期间会返回冲突，取消任务后不会保存尚未提交的分析结果。

## 相关文件

- `demo/agent/`：唯一的核心特征、聚类、质量和排序算法实现。
- `python-api/app/analysis/adapter.py`：把 CullPilot 内部请求转换成 demo 类型，并把 demo 结果转换回 Python/Java 合同。
- `python-api/app/analysis/adapter.py`：唯一的业务适配入口；算法实现统一来自 `demo/agent`。
- `python-api/app/schemas/analysis.py`、`app/api/routes/analysis.py`：内部协议和路由。
- `backend-java/.../integration/AnalysisContract.java`、`PythonApiClient.java`：Java/Python 请求响应契约和 HTTP 调用。
- `backend-java/.../service/job/AnalysisJobRunner.java`：异步任务、结果校验、落库与失败处理。
- `backend-java/.../domain/group/`、`repository/group/`、`service/group/`、`api/group/`：分组数据、查询与权限校验。
- `backend-java/src/main/resources/schema.sql`、`config/DatabaseSchemaMigration.java`：新库建表和已有 SQLite 库的增量迁移。

当前保留 CullPilot 的规则式自然语言解析器和业务数据库缓存；demo 的 LLM provider 与完整目录缓存暂不接入，避免绕过 Java 的项目权限、任务状态和持久化边界。运行测试：`cd python-api; python -m pytest -q`，以及 `cd backend-java; mvn test`。

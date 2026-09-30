# CullPilot 接口设计

版本：MVP v1.0（接口设计稿）

本文档根据《图片筛选项目_技术开发实施方案.md》整理。附件是需求参考，真正的业务入口按当前约定由 Spring Boot 提供；Python 只负责外部大模型/API 适配。

## 1. 服务边界

    浏览器
      -> Spring Boot：唯一的业务入口
           -> Python：内部大模型/API 适配器
                -> 外部模型服务

Spring Boot 负责项目、图片、文件、任务、分析结果、分组、用户决策和导出。Python 不直接访问业务数据库，不直接修改图片状态，也不执行删除。

本版先把基础图片分析视为 Spring Boot 的分析模块能力。以后如果 Pillow/OpenCV 放到 Python，只增加内部接口，不改变浏览器接口。

## 2. 通用约定

### 2.1 地址、字段和 ID

- Spring Boot 公共接口前缀：/api/v1
- Python 内部接口前缀：/api/v1/internal
- JSON 使用 UTF-8，公共接口字段使用 camelCase。
- Python 内部接口沿用当前 Pydantic 模型的 snake_case。
- projectId、assetId、groupId、jobId、exportId 使用 UUID 字符串。
- 时间使用 UTC RFC 3339，例如 2026-09-26T03:20:00Z。
- 分数统一为 0 到 1 的小数；进度统一为 0 到 100 的整数。
- 图片、缩略图和导出文件使用二进制响应，不包在 data 中。

### 2.2 公共响应格式

普通成功响应：

    {
      "data": {},
      "meta": {
        "requestId": "req_01J..."
      }
    }

分页响应：

    {
      "data": {
        "items": [],
        "page": 1,
        "pageSize": 50,
        "total": 126
      },
      "meta": {
        "requestId": "req_01J..."
      }
    }

page 从 1 开始，pageSize 默认 50，最大 100。列表接口不能一次返回项目内所有图片的完整分析 JSON。

错误响应：

    {
      "error": {
        "code": "PROJECT_NOT_FOUND",
        "message": "项目不存在",
        "details": {}
      },
      "meta": {
        "requestId": "req_01J..."
      }
    }

下文的 Spring Boot 资源示例如果只展示资源字段，会省略最外层的 `data` 和 `meta`；实际响应仍遵循上述统一格式。Python 内部接口使用自己的直接响应格式。

### 2.3 重要状态

项目 status：

- created：刚创建，尚未完成上传
- uploading：正在接收图片
- analyzing：分析任务运行中
- ready：可以复核和导出
- failed：最近一次处理失败，可重试
- deleting：正在清理项目文件，暂时禁止其他操作

任务 status：

- queued、running、succeeded、partialFailed、failed、cancelled

图片 decision：

- keep：用户确认保留
- review：待确认，默认值
- reject：用户确认舍弃

图片 recommendation：

- keep、review、reject 或 null

图片 analysisStatus：`pending`、`processing`、`completed`、`failed`。

decision 和 recommendation 必须分开保存。算法只能更新 recommendation，不能覆盖用户 decision，也不能删除原图。

### 2.4 筛选策略

公共接口使用 camelCase：

    {
      "keepPerGroup": 2,
      "strictness": "standard",
      "contentMode": "auto",
      "weights": {
        "sharpness": 0.30,
        "eyesOpen": 0.25,
        "expression": 0.15,
        "exposure": 0.15,
        "composition": 0.10,
        "motion": 0.05
      },
      "constraints": {
        "avoidSevereBlur": true,
        "avoidSevereOverexposure": true,
        "allowMildMotionBlur": true,
        "preferFrontFacing": false
      }
    }

keepPerGroup 范围为 1 到 10，表示每个相似图片组按排名保留的图片数量，不是整个 Project 的总保留数量。strictness 取 loose、standard、strict，用于影响评分和剩余图片的 review/reject 判断；weights 可以省略或传空对象表示使用默认权重；非空权重键必须属于白名单，值范围为 0 到 1。应用策略前服务端补全并归一化权重，响应中返回归一化后的结果。

### 2.5 用户认证

注册和登录接口不需要认证。登录成功后返回 24 小时有效的 Bearer Token，其他公共业务接口必须携带：

    Authorization: Bearer <accessToken>

服务端只在数据库中保存 Token 的 SHA-256 哈希，不保存明文 Token。退出登录会立即撤销当前 Token。项目、图片和分析任务都按当前用户隔离，用户不能访问其他用户的项目。

## 3. Spring Boot 公共接口总览

### 用户认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/auth/register | 注册并自动登录 |
| POST | /api/v1/auth/login | 用户登录 |
| GET | /api/v1/auth/me | 获取当前用户 |
| POST | /api/v1/auth/logout | 退出登录 |

注册请求：

    POST /api/v1/auth/register
    Content-Type: application/json

    {
      "email": "user@example.com",
      "password": "Password123!",
      "displayName": "摄影用户"
    }

注册成功返回 201，登录成功返回 200。两者的 `data` 结构相同：

    {
      "user": {
        "id": "uuid",
        "email": "user@example.com",
        "displayName": "摄影用户",
        "status": "active",
        "createdAt": "2026-09-27T03:20:00Z"
      },
      "tokenType": "Bearer",
      "accessToken": "随机令牌",
      "expiresAt": "2026-09-28T03:20:00Z"
    }

登录请求为 `POST /api/v1/auth/login`，请求体只需 `email` 和 `password`。`GET /api/v1/auth/me` 返回当前用户资料，`POST /api/v1/auth/logout` 撤销当前登录令牌。重复邮箱返回 409 `EMAIL_ALREADY_REGISTERED`，错误密码返回 401 `INVALID_CREDENTIALS`，缺少或无效令牌返回 401 `AUTHENTICATION_REQUIRED`。

新项目自动归属创建人；旧数据库中没有 `owner_id` 的历史项目不会自动分配给任意新账号。上线前应由管理员确认归属后再为这些项目填充 `owner_id`。

### 项目和设置

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/projects | 创建项目 |
| GET | /api/v1/projects | 查询项目列表 |
| GET | /api/v1/projects/{projectId} | 查询项目详情 |
| PATCH | /api/v1/projects/{projectId}/settings | 修改筛选设置 |
| DELETE | /api/v1/projects/{projectId} | 删除项目及文件 |

### 图片和上传

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/projects/{projectId}/assets | 批量上传图片 |
| GET | /api/v1/projects/{projectId}/assets | 分页查询图片 |
| GET | /api/v1/assets/{assetId} | 查询图片详情 |
| GET | /api/v1/assets/{assetId}/thumbnail | 读取缩略图 |
| GET | /api/v1/assets/{assetId}/original | 读取原图 |

### 分析任务

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/projects/{projectId}/analyze | 启动分析 |
| GET | /api/v1/jobs/{jobId} | 查询任务进度 |
| POST | /api/v1/jobs/{jobId}/cancel | 取消任务 |
| POST | /api/v1/jobs/{jobId}/retry | 重试失败任务 |
| GET | /api/v1/projects/{projectId}/summary | 查询项目汇总 |

### 分组、排序和决策

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/v1/projects/{projectId}/groups | 查询分组列表 |
| GET | /api/v1/groups/{groupId}/assets | 查询组内图片 |
| POST | /api/v1/groups/{groupId}/rerank | 重排单个分组 |
| POST | /api/v1/projects/{projectId}/rerank | 重排全部分组 |
| POST | /api/v1/projects/{projectId}/groups/merge | 合并分组 |
| POST | /api/v1/groups/{groupId}/split | 拆分分组 |
| DELETE | /api/v1/groups/{groupId}/assets/{assetId} | 图片移出分组 |
| PATCH | /api/v1/assets/{assetId}/decision | 修改用户决策 |

### 自然语言和导出

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/projects/{projectId}/parse-instruction | 解析自然语言策略 |
| POST | /api/v1/projects/{projectId}/export | 创建导出任务 |
| GET | /api/v1/exports/{exportId} | 查询导出任务 |
| GET | /api/v1/exports/{exportId}/download | 下载导出文件 |

## 4. 项目接口

### 4.1 创建项目

    POST /api/v1/projects
    Content-Type: application/json

请求：

    {
      "name": "2026 春游照片",
      "settings": {
        "keepPerGroup": 2,
        "strictness": "standard",
        "weights": {},
        "constraints": {},
        "privacy": {
          "sendThumbnailsToProvider": false,
          "stripGpsOnExport": true
        }
      }
    }

name 长度为 1 到 100。settings 可以省略，省略字段采用服务端默认值。

响应：201，返回项目对象：

    {
      "data": {
        "id": "uuid",
        "name": "2026 春游照片",
        "status": "created",
        "settings": {},
        "assetCount": 0,
        "groupCount": 0,
        "createdAt": "2026-09-26T03:20:00Z",
        "updatedAt": "2026-09-26T03:20:00Z"
      }
    }

### 4.2 查询项目列表和详情

    GET /api/v1/projects?page=1&pageSize=50&status=ready&sort=createdAt:desc
    GET /api/v1/projects/{projectId}

列表支持 status、page、pageSize 和 sort。列表只返回项目统计摘要，不嵌入全部图片。

详情至少返回：

    {
      "id": "uuid",
      "name": "2026 春游照片",
      "status": "ready",
      "assetCount": 100,
      "groupCount": 18,
      "createdAt": "2026-09-26T03:20:00Z",
      "updatedAt": "2026-09-26T03:35:00Z"
    }

### 4.3 修改设置

    PATCH /api/v1/projects/{projectId}/settings
    Content-Type: application/json

请求是 settings 的部分或完整内容：

    {
      "keepPerGroup": 3,
      "strictness": "loose"
    }

该接口只保存设置，不自动重排。保存后调用项目级 rerank 接口。

### 4.4 删除项目

    DELETE /api/v1/projects/{projectId}

删除数据库记录、原图、缩略图和导出文件。文件清理可能耗时，响应 202 并返回 cleanup job。删除期间不再接受新的上传和分析任务。

## 5. 图片上传和读取接口

### 5.1 批量上传

    POST /api/v1/projects/{projectId}/assets
    Content-Type: multipart/form-data
    Idempotency-Key: upload-batch-001

`file` 是文件类型字段。批量上传时，在同一个 multipart/form-data 请求中重复使用 `file` 字段。后端同时兼容旧字段 `files`：

    file=<a.jpg>
    file=<b.png>
    file=<c.webp>

限制：

- 单张最大 20 MB
- 只接受 JPEG、PNG、WebP
- 单项目最多 1000 张
- 服务端流式写入临时文件，并实际解码校验
- 同一项目内 SHA-256 相同的文件不重复创建和分析

响应：201。一次批量上传允许部分成功：

    {
      "data": {
        "batchId": "uuid",
        "accepted": [
          {
            "id": "uuid",
            "originalName": "a.jpg",
            "mimeType": "image/jpeg",
            "sizeBytes": 1843200,
            "width": 4032,
            "height": 3024,
            "analysisStatus": "pending",
            "decision": "review",
            "recommendation": null,
            "thumbnailUrl": "/api/v1/assets/uuid/thumbnail",
            "originalUrl": "/api/v1/assets/uuid/original"
          }
        ],
        "duplicates": [
          {
            "originalName": "a-copy.jpg",
            "sha256": "...",
            "existingAssetId": "uuid"
          }
        ],
        "failed": [
          {
            "originalName": "bad.gif",
            "code": "UNSUPPORTED_FILE_TYPE",
            "message": "只支持 JPEG、PNG、WebP"
          }
        ]
      }
    }

全部文件失败时返回对应的 4xx 错误。服务器内部路径不出现在响应中。

### 5.2 查询图片列表和详情

    GET /api/v1/projects/{projectId}/assets?page=1&pageSize=50
      &decision=review&recommendation=reject
      &groupId={groupId}&analysisStatus=completed
      &sort=createdAt:asc

支持按 decision、recommendation、groupId 和 analysisStatus 筛选。

单张详情：

    GET /api/v1/assets/{assetId}

返回元数据、EXIF 摘要、分析状态、质量结果、推荐原因、当前 decision 和所属分组。不得返回真实磁盘路径。

建议的质量结果：

    {
      "quality": {
        "score": 0.86,
        "confidence": 0.91,
        "reasons": ["清晰度较高", "曝光正常"],
        "warnings": [],
        "features": {
          "sharpness": 0.88,
          "exposure": 0.82,
          "color": 0.74,
          "faceCount": 2,
          "duplicateDistance": 0.12
        }
      }
    }

### 5.3 读取文件

    GET /api/v1/assets/{assetId}/thumbnail
    GET /api/v1/assets/{assetId}/original

响应为图片二进制流。Spring Boot 必须先校验资源归属，再读取文件；storage 目录不直接作为公共静态目录暴露。

## 6. 分析任务接口

### 6.1 启动分析

    POST /api/v1/projects/{projectId}/analyze
    Content-Type: application/json
    Idempotency-Key: analysis-001

请求：

    {
      "force": false,
      "rebuildGroups": true
    }

没有图片时返回 PROJECT_HAS_NO_ASSETS。同一项目已有运行中的同类任务时返回 409 JOB_ALREADY_RUNNING，并在 details.jobId 返回已有任务。

响应：202，返回任务：

    {
      "data": {
        "id": "uuid",
        "type": "analysis",
        "status": "queued",
        "progress": 0,
        "currentStage": "reading",
        "processedCount": 0,
        "totalCount": 100,
        "errorCount": 0,
        "errorMessage": null,
        "createdAt": "2026-09-26T03:20:00Z",
        "finishedAt": null
      }
    }

分析顺序固定为：读取文件、生成缩略图、读取 EXIF、提取特征、相似分组、质量评分、组内排序、写入结果。

### 6.2 查询、取消和重试任务

    GET /api/v1/jobs/{jobId}
    POST /api/v1/jobs/{jobId}/cancel
    POST /api/v1/jobs/{jobId}/retry

任务查询返回 progress、currentStage、processedCount、totalCount 和可展示的错误列表。

只有 queued 或 running 任务可以取消；只有 failed、partialFailed 或 cancelled 任务可以重试。已生成的缩略图和已完成结果应尽量复用。

### 6.3 项目汇总

    GET /api/v1/projects/{projectId}/summary

响应：

    {
      "projectId": "uuid",
      "status": "ready",
      "assetCount": 100,
      "analyzedCount": 99,
      "groupCount": 18,
      "decisionCounts": {
        "keep": 20,
        "review": 60,
        "reject": 20
      },
      "recommendationCounts": {
        "keep": 22,
        "review": 58,
        "reject": 20
      },
      "activeJobId": null,
      "warnings": ["1 张图片读取失败"]
    }

## 7. 分组、排序和手动修正

### 7.1 查询分组

    GET /api/v1/projects/{projectId}/groups?page=1&pageSize=50&sort=confidence:desc

响应中的单个分组：

    {
      "id": "uuid",
      "projectId": "uuid",
      "groupNo": 1,
      "assetCount": 6,
      "confidence": 0.93,
      "timeRange": {
        "from": "2026-09-26T03:20:00Z",
        "to": "2026-09-26T03:20:18Z"
      },
      "reasons": ["拍摄时间接近", "颜色相似"],
      "recommendedAssetIds": ["uuid", "uuid"]
    }

### 7.2 查询组内图片

    GET /api/v1/groups/{groupId}/assets?page=1&pageSize=50&sort=rank:asc

每个成员返回 rank、recommendScore、membershipReason、图片质量、推荐原因和用户 decision。

### 7.3 重排

单组重排：

    POST /api/v1/groups/{groupId}/rerank
    Content-Type: application/json

    {
      "keepPerGroup": 3,
      "strictness": "loose",
      "strategy": {}
    }

只影响当前分组，不修改用户 decision。响应 200 返回更新后的组内成员。

项目级重排：

    POST /api/v1/projects/{projectId}/rerank
    Content-Type: application/json

    {
      "strategy": {
        "keepPerGroup": 2,
        "strictness": "standard",
        "weights": {},
        "constraints": {}
      },
      "saveAsProjectSettings": true
    }

项目级重排可能涉及大量图片，响应 202 并返回 job。saveAsProjectSettings 为 true 时同时保存策略。

### 7.4 合并、拆分和移出

合并：

    POST /api/v1/projects/{projectId}/groups/merge

    {
      "groupIds": ["uuid", "uuid"]
    }

至少传两个属于同一项目的分组。合并后重新计算受影响分组的成员排序。

拆分：

    POST /api/v1/groups/{groupId}/split

    {
      "newGroupAssetIds": ["uuid", "uuid"]
    }

指定图片移入新分组，原分组保留其余图片。不能把全部或零张图片移出。

移出：

    DELETE /api/v1/groups/{groupId}/assets/{assetId}

只移除分组关系，不删除图片文件。图片进入未分组状态。

## 8. 用户决策接口

    PATCH /api/v1/assets/{assetId}/decision
    Content-Type: application/json

请求：

    {
      "decision": "keep",
      "expectedVersion": 3
    }

decision 只能是 keep、review、reject。把 reject 改回 keep 或 review 就是“恢复”操作，不需要单独的恢复接口。每次修改写入 decisions 历史，source 固定为 user。

如果 expectedVersion 不一致，返回 409 VERSION_CONFLICT，避免多个页面相互覆盖。

## 9. 自然语言策略接口

### 9.1 Spring Boot 公共接口

    POST /api/v1/projects/{projectId}/parse-instruction
    Content-Type: application/json

请求：

    {
      "text": "每组保留两张最清晰、人物睁眼的照片",
      "allowedFeatures": ["sharpness", "eyesOpen", "keepPerGroup"]
    }

该接口只返回策略预览，不修改项目设置、不修改图片 decision，也不执行删除。

响应：

    {
      "data": {
        "text": "每组保留两张最清晰、人物睁眼的照片",
        "strategy": {
          "keepPerGroup": 2,
          "strictness": "standard",
          "contentMode": "auto",
          "weights": {
            "sharpness": 0.55,
            "eyesOpen": 0.30,
            "exposure": 0.15
          },
          "constraints": {
            "avoidSevereBlur": true
          }
        },
        "confidence": 0.91,
        "unsupportedTerms": [],
        "explanation": "已将“最清晰”和“睁眼”映射为清晰度与睁眼指标。",
        "fallbackUsed": false
      }
    }

解析成功后，前端可以把 strategy 交给 settings 或 rerank 接口；这两个动作必须是明确的后续请求。

    Python 不可用时返回 503 INSTRUCTION_PARSER_UNAVAILABLE。用户仍可使用结构化设置和重排接口。

当前实现说明：该接口已经接通，但默认使用 Python 内置规则解析器，不依赖外部 LLM 或 CLIP 权重。它支持把“每组保留两张最清晰、人物睁眼的照片”等描述映射为现有的清晰度、睁眼、表情、曝光、构图和筛选约束。返回结果只是预览，前端确认后仍需调用 `PATCH /api/v1/projects/{projectId}/settings` 保存策略，再调用分析接口。

如果 `text` 省略或为空，接口返回 `fallbackUsed=true`。分析时如果项目没有自定义权重，Python 会根据实际检测结果按分组选择默认策略：有人脸的分组使用人像默认权重，无人脸的分组使用风景默认权重。一个 mixed 项目可以同时使用两套默认权重。

### 9.2 Python 内部接口

    POST http://localhost:8001/api/v1/internal/llm/parse-instruction
    Content-Type: application/json
    X-Request-Id: req_01J...
    X-Internal-Token: ...

请求使用 Python 风格字段：

    {
      "text": "每组保留两张最清晰的照片",
      "allowed_features": ["sharpness", "keep_per_group"]
    }

响应 200：

    {
      "strategy": {
        "keep_per_group": 2,
        "strictness": "standard",
        "content_mode": "auto",
        "weights": {
          "sharpness": 0.7,
          "exposure": 0.3
        },
        "constraints": {
          "avoid_severe_blur": true
        },
        "unsupported_terms": [],
        "explanation": "将“最清晰”映射为清晰度权重。"
      },
      "confidence": 0.95,
      "fallback_used": false,
      "provider": "builtin",
      "model": "rule-based-v1"
    }

Python 必须执行 JSON 解析、Pydantic 类型和范围、白名单业务三层校验。不能返回文件路径、删除指令或数据库操作。

当前 Python 接口已经由内置规则解析器实现并返回 200；后续可以在不改变公共接口的前提下替换为外部 Provider。

## 10. 导出接口

### 10.1 创建导出任务

    POST /api/v1/projects/{projectId}/export
    Content-Type: application/json

请求：

    {
      "selection": "keep",
      "copyImages": true,
      "stripGps": true,
      "includeManifest": true,
      "manifestFormat": "csv"
    }

selection 取 keep 或 keepAndReview。响应 202，返回 exportId 和任务信息。

### 10.2 查询和下载

    GET /api/v1/exports/{exportId}
    GET /api/v1/exports/{exportId}/download

导出 status 为 queued、running、succeeded 或 failed。只有 succeeded 才能下载。导出失败不回滚用户 decision，可以重新创建导出任务。

导出图片的最终判断按以下优先级确定：有用户操作历史时使用 `decision`；用户未操作时使用 AI 的 `recommendation`；两者都没有时按 `review` 处理。因此导出不会覆盖用户明确作出的决定。

当前实现生成 ZIP；解压后所有内容位于 `cullpilot-export/` 文件夹中，图片按分组放入 `group-001/`、`group-002/` 等子文件夹，未分组图片放入 `ungrouped/`。`copyImages=true` 时包含选中原图，`includeManifest=true` 时在 `cullpilot-export/manifest.csv` 生成清单。`stripGps=true` 时通过重新编码图片移除元数据，除了 GPS 之外的 EXIF 信息也会被移除。`stripGps` 省略时使用项目隐私设置。

## 11. 错误码

| HTTP | code | 含义 |
|---|---|---|
| 404 | PROJECT_NOT_FOUND | 项目不存在 |
| 404 | ASSET_NOT_FOUND | 图片不存在或不属于当前项目 |
| 400 | PROJECT_HAS_NO_ASSETS | 项目没有可分析图片 |
| 415 | UNSUPPORTED_FILE_TYPE | 不是 JPEG、PNG 或 WebP |
| 413 | FILE_TOO_LARGE | 单文件超过大小限制 |
| 409 | PROJECT_ASSET_LIMIT_EXCEEDED | 超过项目图片数量上限 |
| 409 | JOB_ALREADY_RUNNING | 当前项目已有同类任务运行 |
| 409 | VERSION_CONFLICT | 用户决策版本已被更新 |
| 400 | INVALID_GROUP_OPERATION | 分组操作参数不合法 |
| 422 | INVALID_STRATEGY | 策略字段、范围或权重不合法 |
| 503 | LLM_UNAVAILABLE | Python 或外部模型暂时不可用 |
| 507 | STORAGE_INSUFFICIENT | 存储空间不足 |

## 12. 必须保持的不变量

1. 浏览器只调用 Spring Boot，API Key 不返回前端。
2. 每个资源操作都校验资源归属，不能只凭 assetId 访问其他项目的数据。
3. decision 和 recommendation 分离；AI 不能覆盖用户决策，不能自动删除原图。
4. 原图和缩略图通过受控接口读取，响应中不暴露真实磁盘路径。
5. 同一项目同一 SHA-256 只保留一份图片记录。
6. 长任务通过 jobId 查询，前端不长时间占用上传或分析请求。
7. 外部模型不可用时，本地上传、基础分析、分组、复核和导出仍可工作。
8. 分页、状态枚举、分数单位、时间格式和字段含义不能由单个模块自行改变。
9. 手动合并、拆分和移出只改变分组关系，不删除图片；受影响分组要重新计算排序。
10. 自然语言解析是策略预览；应用策略必须由后续明确请求完成。

## 13. 推荐实现顺序

1. 创建项目、上传图片、生成并读取缩略图。
2. 启动分析、查询任务、查询项目汇总。
3. 图片列表、分组列表、组内图片详情。
4. 单图决策、单组重排和项目级重排。
5. 分组合并、拆分和移出。
6. 替换内置 parse-instruction 规则解析器为可选的真实 Provider，并补充更多业务词汇校验。
7. 导出任务、下载和项目清理。

这条顺序对应“创建项目 -> 上传 -> 分析 -> 复核 -> 导出”的最小闭环。

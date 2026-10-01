# CullPilot

当前已实现项目管理、图片上传、分析与相似分组、用户复核、AIGC 图片美化和导出。AI 分析的运行要求、接口和各文件职责见 [AI 图片筛选适配说明](docs/ai-analysis.md)。

图片筛选项目的后端工程。

当前架构：

- `backend-java`：Spring Boot 主后端，负责项目、图片、分析、分组、决策、AIGC 编辑任务和导出等业务。
- `python-api`：Python API 适配服务，负责分析和调用外部图像编辑模型。
- `../demo/agent`：CullPilot 当前使用的核心图片分析算法模块。
- `docs`：架构说明、接口边界和目录说明。
- `storage`：原图、缩略图和导出文件的本地存储根目录。
- `data`：本地 SQLite 数据库文件目录。

AIGC 编辑只对最终判断为 keep/review 的已分析或已人工决策图片开放。生成图独立保存，不替换原图；导出 ZIP 保留原有 `cullpilot-export/group-*` 和 `ungrouped` 目录，并增加 `cullpilot-export/aigc/`。接口与请求示例见 [API 合约](docs/api-contract.md) 和 [Apifox OpenAPI](docs/apifox-openapi.yaml)。

## 启动方式

### Python API 服务

```powershell
cd python-api
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements-ai.txt
uvicorn app.main:app --reload --port 8001
```

服务地址：`http://localhost:8001`

模型与依赖状态：`http://localhost:8001/api/v1/health/models`

### Spring Boot 主后端

需要本机安装 Maven 3.9+：

```powershell
cd backend-java
mvn spring-boot:run
```

服务地址：`http://localhost:8080`

如果使用 IntelliJ IDEA，可以直接打开 `backend-java/pom.xml`，等待 Maven 同步后运行
`com.cullpilot.backend.CullPilotApplication`。

## 与原实施方案的关系

原方案中后端技术栈写的是 FastAPI。本项目根据新的要求调整为：

```text
浏览器
  -> Spring Boot 主后端
       -> Python API 适配服务
            -> 外部大模型 / 视觉 API
```

Spring Boot 维护业务边界和数据库边界，Python 只负责外部 AI/API 适配，避免把供应商 SDK、
API Key 和第三方响应格式扩散到 Java 业务代码中。

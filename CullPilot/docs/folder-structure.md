# 文件夹说明

```text
CullPilot/
├── backend-java/
│   ├── src/main/java/com/cullpilot/backend/
│   │   ├── api/             # Spring MVC 对外接口
│   │   │   ├── auth/        # 注册、登录、当前用户和退出登录
│   │   │   └── job/         # 分析任务接口和响应模型
│   │   ├── config/          # Spring 配置和环境变量绑定
│   │   ├── security/        # Bearer Token 过滤器和鉴权错误响应
│   │   ├── integration/     # 调用 Python API 的客户端边界
│   │   ├── analysis/        # 预留：图片分析和评分
│   │   ├── domain/          # 领域对象，按业务聚合分包
│   │   │   ├── project/     # 项目领域模型、设置和状态
│   │   │   ├── asset/       # 图片资源和图片分析状态
│   │   │   ├── job/         # 后台任务和任务错误
│   │   │   └── user/        # 用户和登录会话
│   │   ├── repository/      # 数据访问接口，按业务聚合分包
│   │   │   ├── project/     # 项目 Repository
│   │   │   ├── asset/       # 图片 Repository
│   │   │   ├── job/         # 任务 Repository
│   │   │   └── user/        # 用户和会话 Repository
│   │   └── service/         # 应用服务，按业务聚合分包
│   │       ├── project/     # 项目生命周期和存储服务
│   │       ├── asset/       # 图片上传、存储和读取服务
│   │       ├── job/         # 分析任务生命周期和后台执行器
│   │       └── auth/        # 密码哈希、Token 和登录会话
│   ├── src/main/resources/  # application.yml、数据库迁移脚本
│   └── src/test/            # Spring Boot 集成测试
├── python-api/
│   ├── app/
│   │   ├── api/routes/      # Python 服务 HTTP 路由
│   │   ├── providers/       # 外部模型/API Provider 实现
│   │   ├── schemas/         # Pydantic 请求和响应模型
│   │   └── services/        # Python 侧应用服务
│   └── tests/               # Python 服务测试
├── data/                    # SQLite 文件，不提交真实数据库
├── storage/                 # 原图、缩略图和导出文件
├── docs/                    # 架构和接口说明
└── .env.example             # 环境变量模板
```

## 后续业务目录建议

Spring Boot 后续可按以下边界继续扩展，每个业务边界在 domain、repository、service 和 api 下使用对应子包：

- `domain/project`：项目
- `domain/asset`：图片资源
- `domain/job`：后台分析任务和任务错误
- `domain/analysis`：后续补充具体分析结果和评分模型
- `domain/group`：相似图片分组
- `domain/decision`：保留、待确认、建议舍弃
- `domain/export`：导出任务

这种拆分只是 Java 包结构，不代表现在就拆成多个微服务。服务仍然保持在同一个 Spring Boot 应用中。

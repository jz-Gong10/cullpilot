# 架构说明

## 服务职责

### Spring Boot 主后端

Spring Boot 是唯一面向浏览器的业务后端，未来负责：

- 项目创建、项目状态和项目配置
- 用户注册、登录、Token 会话和项目归属校验
- 图片上传、文件校验、原图和缩略图路径管理
- SQLite 数据访问
- 分析任务状态和进度
- 图片特征、质量评分、相似分组和用户决策
- 导出任务和受控图片读取
- 将自然语言筛选请求转发给 Python API 适配服务

### Python API 适配服务

Python 服务只负责外部 API 适配，未来负责：

- LLM/视觉 API 的统一 Provider 接口
- 外部供应商请求、超时、重试和响应解析
- Pydantic 结构化校验
- API 不可用时的降级结果

Python 服务不直接管理项目数据库，也不直接决定删除图片。

## 调用方向

```text
Browser -> Spring Boot -> Python API adapter -> External provider
```

浏览器不直接访问 Python 服务，API Key 只配置在 Python 服务环境变量中。

## 认证方式

浏览器调用注册或登录接口后获得 Bearer Token，后续请求通过 `Authorization` 请求头携带 Token。密码使用 BCrypt 哈希保存；Token 明文只在注册或登录响应中返回，数据库保存 Token 哈希和过期时间。

## 当前实现范围

当前只有两个健康检查入口和一个返回 `501 Not Implemented` 的 LLM 合同占位接口。
图片、数据库实体、分析算法、导出和真正的第三方 API 调用留待后续迭代。

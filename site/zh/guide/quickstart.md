# 快速开始

## 一键全家桶（推荐）

仓库根目录两个脚本拉起全部服务——PostgreSQL 17 + pgvector、Redis、Gitea、引擎与控制台：

```bash
./start-all.sh    # 启动全家桶
./stop-all.sh     # 停止全部
```

首次启动后的日常使用：

```bash
./start-all.sh
# 在 Gitea 打开一个 PR → 引擎自动审查
./stop-all.sh
```

## 手动启动

### 1. 构建

```bash
./mvnw -o compile
```

### 2. 配置

把 `application.yml`（或环境变量）指向你的基础设施：

- PostgreSQL 17 + pgvector（记忆与持久化）
- Redis（队列）
- Gitea / GitLab 地址与 token
- TokenHub API key（模型网关）

### 3. 运行

```bash
./mvnw -o spring-boot:run -Dspring-boot.run.arguments="--gitea.base-url=http://localhost:3000 --gitea.api-token=<token> --server.port=8080"
```

然后在代码托管平台注册 webhook，让 PR 事件到达引擎。具体步骤见[代码托管平台接入](./integration)。

## 验证

```bash
curl localhost:8080/health
```

webhook 触发后，引擎日志会打出完整审查链路——各 Agent 启动、发现数量、报告回写；Gitea 的 PR 页面会出现行内评论。

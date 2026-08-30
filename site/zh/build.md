# 构建与测试

## 环境要求

- JDK 17、Maven
- PostgreSQL 17 + pgvector、Redis、Gitea（完整本地栈用 `start-all.sh`）

## 常用命令

```bash
./mvnw -o compile            # 离线编译
./mvnw -o test               # 全量测试（130 例，31 个测试类，无需外部基础设施）
```

## 测试亮点

- **状态机** —— 440 种转移组合，覆盖 11 个测试类
- **规划** —— DAG 拓扑序、环拒绝、失败传播
- **工具调用** —— 决策循环语义、路径穿越拒绝
- **Agent 能力** —— 反思库、precision/recall 断言、扩展注册排序

详细报告见 `docs/test-report-*.md`。

## IDE 设置

如果 IntelliJ 到处报红，先跑一次 `./mvnw -o compile` 预热 classpath——多模块 Spring Boot 项目首次打开时的已知现象。

## 发布说明

这是应用仓库（不是库），不做 Maven Central 发布——发布以 git 版本标签为准。其可复用组件在独立的 `agent-kit` 仓库，已发布到 Maven Central：`io.github.13liyunfei:agent-kit:0.1.0`。

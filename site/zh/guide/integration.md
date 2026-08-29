# 代码托管平台接入

## Gitea（本地推荐）

1. 启动 Gitea 并创建管理员 token
2. 引擎以 `--gitea.base-url` 与 `--gitea.api-token` 启动
3. 在 Gitea 仓库设置里注册 webhook，指向引擎的 webhook 端点

```bash
# webhook 地址示例
http://<引擎地址>:8080/webhook/gitea
```

之后每个 `pull_request` 事件都会自动触发审查。

## GitLab

1. 创建 GitLab Personal Access Token
2. 引擎配置 `gitlab.base-url` / `gitlab.api-token`
3. 在项目设置注册 merge-request webhook

## Webhook 流程

```
PR 打开 → webhook → 引擎鉴权 + 解析团队
        → Coordinator → 5 个 Agent 并行
        → 聚合 / 仲裁 / 排序
        → 报告 + 行内评论写回 SCM
```

## Gitea 1.27 的行内评论适配

Gitea 的 PR review API 有已知怪癖——PENDING 预建 review 里的行级评论会被服务器丢弃，因此引擎发布：

- **顶层概览评论**
- **文件级修复建议**（带修复文案）

在所有 Gitea 版本上都可用，审查者仍能一键看到修复方案。

## IDE 集成

`IdeReviewServer`（LSP 风格）复用同一套 `AstAnalyzer` 与规则集，保证 IDE 与 CI 审查口径一致。运行 `com.codereview.agent.ide.IdeReviewServer` 后接 LSP 客户端即可。

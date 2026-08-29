# SCM integration

## Gitea (recommended for local)

1. Start Gitea and create an admin token
2. Start the review engine with `--gitea.base-url` and `--gitea.api-token`
3. In the Gitea repo settings, register a webhook pointing at the engine's webhook endpoint

```bash
# example webhook payload URL
http://<engine-host>:8080/webhook/gitea
```

Every `pull_request` event now triggers a review automatically.

## GitLab

1. Create a GitLab Personal Access Token
2. Configure `gitlab.base-url` / `gitlab.api-token` in the engine
3. Register the merge-request webhook in the project settings

## Webhook flow

```
PR opened → webhook → engine authenticates + resolves team
        → Coordinator → 5 agents in parallel
        → aggregate / arbitrate / rank
        → report + inline comments written back to SCM
```

## Inline comments on Gitea 1.27

Gitea's PR review API has quirks — line-level comments inside a pending review are dropped by the server, so the engine publishes:

- the **top-level summary** comment
- **file-level suggestions** with the fix text

This works on all Gitea versions and still gives reviewers one-click access to the fix.

## IDE integration

An `IdeReviewServer` (LSP-style) reuses the same `AstAnalyzer` and rule set, so IDE and CI review standards stay consistent. Run `com.codereview.agent.ide.IdeReviewServer` and connect an LSP client.

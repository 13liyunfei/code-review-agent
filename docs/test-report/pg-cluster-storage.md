# 测试报告：彻底移除本地文件存储 · 状态迁 PostgreSQL · 记忆升级与遗忘

> 分支：`feature/pg-cluster-storage` ｜ 基准：`main` ｜ 关联 PR：#1
> 验证日期：2026-09-06 ｜ 环境：macOS + 本地 PostgreSQL 17.11（pgvector 0.8.6）+ Redis + Gitea 1.27（localhost:3000）

## 1. 结论（TL;DR）

改造前审查状态（断点/经验/校准/反馈/审查历史/轨迹/团队配置/知识元数据）散落 `data-dir` 本地 JSON 文件，
**多实例部署下 A 机沉淀、B 机不可见，必然分叉**。本次改造把所有状态收敛到共享 PostgreSQL
（`pgvector.enabled=true` 装配，8 张状态表幂等建表），并补齐「记忆升级与遗忘」生命周期。验证结论：

- ✅ **266 项测试全绿**（含 5 项「真实 PG、A/B 双连接」集群一致性 E2E）
- ✅ **真实 Gitea PR 审查 E2E**：审查轨迹 17 事件落 PG、反思沉淀 12 条经验
- ✅ **A 机沉淀 → B 机可见**：实例 B 重启后经管理端点读到实例 A 沉淀的全部 12 条经验
- ✅ **`data-dev` 目录零文件写入**（本地文件存储确实被移除）
- ✅ 记忆升级（证据 ≥3 → ACTIVE）、误报降级（≥2 → ARCHIVED）、TTL 软删→硬删、命中刷新反遗忘全部落地并有测试与真库证据

## 2. 覆盖矩阵

| # | 验证点 | 类型 | 结果 | 证据 |
|---|--------|------|------|------|
| 1 | 断点续跑：A 实例写 → B 实例同 runId 续跑 | 单测 + 真库 E2E | ✅ | `ResumeStoreTest`(7)；`PgClusterE2ETest#resumeAndTrajectorySharedAcrossInstances` |
| 2 | 审查轨迹：A 写事件序列 → B 读（审计/回放不依赖写入实例） | 单测 + 真库 E2E | ✅ | `TrajectoryTest`(4)；E2E 同上；真机轨迹 17 事件入库 |
| 3 | 团队配置（自定义 Agent/规则/启停）：A 配置 → B 生效 | 单测 + 真库 E2E | ✅ | `CustomAgentStoreTest`(5)、`SkillRegistryTest`(5)；E2E `customAgentAndSkillConfigSharedAcrossInstances` |
| 4 | 反馈落库 → 校准派生状态跨实例共享（不再乘 1.0 空转） | 单测 + 真库 E2E | ✅ | `CalibrationFeedbackLoopTest`(8)；E2E `feedbackAndCalibrationSharedAcrossInstances`（0.8 跨实例恢复） |
| 5 | 记忆升级：复现/正报证据 ≥3 → ACTIVE（SQL 原子） | 单测 + 真库 E2E | ✅ | `ExperienceLifecycleTest`；E2E `experienceLifecycleEvidenceUpgradeForgetAndPurgeOnPg` |
| 6 | 记忆遗忘：误报 ≥2 → ARCHIVED；TTL 软删；保留期硬删 | 单测 + 真库 E2E | ✅ | `ExperienceLifecycleTest`(8)；E2E 同 #5（真库 UPDATE 回拨时间验证） |
| 7 | spaced-repetition 反遗忘：检索命中刷新 lastHitAt | 单测 + 真库 E2E | ✅ | `ExperienceLifecycleTest#recordHitRefreshesSpacedRepetitionClock` |
| 8 | 生产装配不歧义（双 FeedbackListener → @Primary 复合监听） | 启动验证 | ✅ | 引擎启动成功；修复前 `APPLICATION FAILED`（NoUniqueBean），修复后正常 |
| 9 | 真实 Webhook PR 全链路审查（LLM+索引+RAG+反思） | 真实 E2E | ✅ | PR `reviewer/demo-project#44`，5 Agent 并行、影响面索引、混合检索、行内评论发布 11 条 |
| 10 | 审查状态不落本地文件 | 真实 E2E | ✅ | `data-dev` 目录自始至终仅 `.gitkeep`，零业务文件 |
| 11 | 引擎重启后（实例 B）读到 A 实例沉淀 | 真实 E2E | ✅ | B 实例 `GET /api/admin/memory/experiences?team=default` 返回 **12 条** |
| 12 | 无 PG 环境回退：内存实现仍团队隔离、套件仍可跑 | 单测 | ✅ | `PgClusterE2ETest#inMemoryFallbackStillIsolated`；无 PG 时整类 assumeTrue 跳过（CI 友好） |

## 3. 单元测试结果

```
Tests run: 266, Failures: 0, Errors: 0, Skipped: 0   （含 e2e.PgClusterE2ETest 5 项真库用例）
```

新增/改写关键测试：`ExperienceLifecycleTest`（8，全新）、`ResumeStoreTest`(7)、`ResumeJanitorTest`(3)、
`SkillRegistryTest`(5)、`CustomAgentStoreTest`(5)、`CalibrationFeedbackLoopTest`(8)、
`CoordinatorEnhancement/CustomAgent/Planning`、`TrajectoryTest`(4)、`ReviewReplayTest`(3)、
`PgClusterE2ETest`(5，全新) 等。原 `File*Store`/`TeamMailbox` 相关文件语义测试随实现删除同步移除。

## 4. 真实 Gitea PR E2E 证据

### 4.1 环境与样例
- 仓库：`reviewer/demo-project`（本地 Gitea），新分支 + PR #44，新增 `PaymentService.java`（含 SQL 拼接、System.out、TODO、NPE-on-connection 等典型问题）
- 引擎：dev profile + `pgvector.enabled=true`（base 默认）+ `review.reflection.enabled=true`

### 4.2 关键日志（节选）
```
[PgDb] 已创建统一持久化连接池（localhost:5432/codereview, 最大连接=12）
[PgDb] 状态表已就绪（resume_state / team_kv / experience_entry / review_feedback
       / review_history / trajectory_store / knowledge_meta / calibration_accuracy）
[Coordinator] 开始审查 PR#44（…runId=reviewer_demo-project#44@5615001a…，Agent 数=5）
[RepoIndex] 索引完成：拉取 2 文件，分析成功 2，失败 0，跨文件能力=true
[PgKnowledge] 混合检索：team=default, topK=10, 融合命中 10 条
[Coordinator] PR#44 审查完成（…）：最终 12 条，抑制误报 0 条，仲裁覆盖 9 条，降级环节 0 个
[PgTrajectoryStore] 轨迹已入库：runId=…5615001a…, teamId=default, 事件数=17
[Gitea审查] 步骤[发布行内评论] 完成：已发布 11 条（共 11 条可修复）
[Reflection] 团队 default 反思沉淀 12 条经验
```

### 4.3 PG 状态核验（审查完成后直接查询）
```sql
select run_id, team_id, jsonb_array_length(events) from trajectory_store;   -- 17 事件 / default
select count(*) from review_history;                                          -- 1（PG 历史）
select count(*) from resume_state where team_id='default';                    -- 0（正常完成即清理）
select stage, count(*) from experience_entry group by stage;                  -- candidate | 12
```

### 4.4 A/B 双实例（核心场景复现）
1. 实例 A 完成上述审查并反思沉淀 12 条经验（`experience_entry`，stage=candidate, evidence_pos=1）；
2. **停止实例 A，重启为实例 B**（全新进程、全新内存态，仅共享同一 PG）；
3. 实例 B 管理端点核验：
```
GET /api/admin/memory/experiences?team=default  →  12 条全部可见
- CANDIDATE 1 高频问题共性总结
- CANDIDATE 1 ARCH-002 SQL拼接导致注入风险与架构耦合 src/main/java/com/demo/PaymentServ…
- CANDIDATE 1 SECURITY-HARDCODED-CREDENTIALS 硬编码数据库用户名和URL …
…
```
→ **A 机沉淀的经验在 B 机立即可见**：改造前该场景必然失败（B 机本地无 experience.json）。

### 4.5 零文件写入核验
`data-dev/`（原 data-dir 观察目录）在整个 E2E 期间仅含初始占位 `.gitkeep`，无任何业务 JSON/轨迹文件产生。

## 5. 变更清单

**删除（本地文件存储及死代码）**：`FileResumeStore` / `FileFeedbackStore` / `FileReviewHistoryStore` / `TeamMailbox`（及 `core/mailbox/` 整包、镜像测试）。

**新增 `core/store/`（存储后端）**：`PgDb`（统一连接池 + 8 表幂等建表）、`StateStoreConfig`/`PersistenceConfig`（按 `pgvector.enabled` 装配）、`ResumeStore`/`TrajectoryStore`/`TeamConfigStore`/`CalibrationStore`/`ExperienceLibrary`/`FeedbackStore`/`ReviewHistoryStore`/`KnowledgeMetaStore` 接口 + `Pg*`/`InMemory*` 双实现。

**记忆生命周期（`core/memory/`）**：`ExperienceStage`（CANDIDATE/ACTIVE/ARCHIVED/PURGED）、`ExperienceEntry`、`ExperienceLibrary`（含 `PgExperienceLibrary` SQL 原子升级/遗忘）、`MemoryMaintenanceScheduler`、`ExperienceAdminController`、`ExperienceStore` 检索「命中即 recordHit」+ 反思接库。

**装配**：`ReviewAgentConfig`（依赖接口、复合 `FeedbackListener` 标 `@Primary`）、`GiteaConfig`（注入 `ExperienceStore`、反思开关 `review.reflection.enabled`）、`ReviewEnhancements` 3 字段记录（去 TeamMailbox）。

## 6. 回归指引 / CI 说明

- 全量单测：`unset HTTP_PROXY HTTPS_PROXY && NO_PROXY=localhost,127.0.0.1 ./mvnw -o test`
- 真库集群 E2E（需本地 PG+pgvector，缺库自动跳过不红）：`./mvnw -o test -Dtest=PgClusterE2ETest`
- 真实 PR E2E 复现：`./mvnw -o spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments="--token-factory.enabled=false --review.reflection.enabled=true"`，在 Gitea 仓库开 PR 后核对 `trajectory_store`/`experience_entry`/`resume_state` 与 `data-dev` 目录。
- CI（GitHub Actions 无 PG）：`PgClusterE2ETest` 整类 `assumeTrue` 跳过，不影响全量绿。

## 7. 风险与后续

- `pgvector.enabled=false`（未配 PG）时状态退化为内存实现，**多实例部署必须启用 PostgreSQL**（启动日志会 WARN 提示）。
- 校准/经验的维护调度（TTL 硬删等）由 `MemoryMaintenanceScheduler` 按配置 cron 执行；调度未开启时仅软删不硬删，数据不丢失。
- `review.data-dir` 属性保留：现仅服务于可选 `FileReadTool`（tools agent-loop 关闭时无任何文件写入）。

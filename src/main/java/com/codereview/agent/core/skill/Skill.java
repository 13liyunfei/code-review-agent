package com.codereview.agent.core.skill;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.ReviewContext;

import java.util.List;

/**
 * 技能（Skill）插件抽象。
 *
 * <p>依据文档“Skills 插件化架构”：每个 Skill 是一段确定性的静态扫描能力
 * （如密钥检测、SQL 注入检测），由 Agent 在调用 LLM 之前预执行，
 * 产出高置信、可解释的发现，再由 LLM 做补充与去噪。
 */
public interface Skill {

    /**
     * 获取技能元数据（名称、描述、分类）。
     *
     * @return 技能元信息
     */
    SkillMetadata getMetadata();

    /**
     * 对变更代码执行该技能。
     *
     * @param diffs 代码变更列表
     * @param ctx   审查上下文
     * @return 该技能产出的检测结果（可能为空）
     */
    List<SkillResult> execute(List<CodeDiff> diffs, ReviewContext ctx);
}

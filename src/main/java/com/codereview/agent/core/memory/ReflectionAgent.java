package com.codereview.agent.core.memory;

import com.codereview.agent.tenant.Teams;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 反思代理（ReflectionAgent，见文档“三层记忆架构”）。
 *
 * <p>审查完成（或定时任务）后，根据开发者反馈自动沉淀“经验”，写入长期记忆，
 * 供后续 PR 检索复用，实现“越用越准”。经验按团队隔离。
 */
@Component
public class ReflectionAgent {

    /**
     * 将一组反馈反思为经验条目（长期记忆）。
     *
     * @param teamId    团队标识（经验仅对该团队可见）
     * @param feedbacks 开发者反馈列表
     * @return 待写入长期记忆的经验条目
     */
    public List<MemoryEntry> reflect(String teamId, List<ReviewFeedback> feedbacks) {
        String t = Teams.sanitize(teamId);
        List<MemoryEntry> experiences = new ArrayList<>();
        for (ReviewFeedback fb : feedbacks) {
            String content = fb.isFalsePositive()
                    ? String.format("规则 %s（%s）曾被人工标记为误报，建议降低其置信度权重。备注：%s",
                    fb.ruleId(), fb.agentType(), fb.note())
                    : String.format("规则 %s（%s）被人工确认为有效问题，可保持或提升置信度。备注：%s",
                    fb.ruleId(), fb.agentType(), fb.note());
            experiences.add(new MemoryEntry(
                    null, fb.agentType(), t, content,
                    Map.of("type", "experience", "ruleId", fb.ruleId()),
                    MemoryLevel.LONG_TERM, Instant.now(), null));
        }
        return experiences;
    }
}

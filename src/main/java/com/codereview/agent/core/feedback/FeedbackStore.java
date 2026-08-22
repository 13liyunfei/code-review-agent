package com.codereview.agent.core.feedback;

import com.codereview.agent.core.memory.ReviewFeedback;

import java.util.List;

/**
 * 反馈存储（误报反馈闭环的持久化入口）。
 *
 * <p>负责保存开发者对审查发现的反馈，并在聚合阶段提供“已确认的误报”列表，
 * 用于抑制重复误报。实现可基于本地文件（{@code FileFeedbackStore}）或内存
 * （{@code InMemoryFeedbackStore}，用于无盘或测试环境）。所有数据按团队隔离。
 */
public interface FeedbackStore {

    /**
     * 保存某团队的一条反馈。
     *
     * @param teamId   团队标识
     * @param feedback 反馈条目
     */
    void save(String teamId, ReviewFeedback feedback);

    /**
     * 列出某团队的全部反馈（含误报与正报）。
     *
     * @param teamId 团队标识
     * @return 反馈列表
     */
    List<ReviewFeedback> list(String teamId);

    /**
     * 获取某团队所有被标记为“误报”的反馈，供聚合阶段抑制使用。
     *
     * @param teamId 团队标识
     * @return 误报反馈列表
     */
    default List<ReviewFeedback> falsePositives(String teamId) {
        return list(teamId).stream().filter(ReviewFeedback::isFalsePositive).toList();
    }
}

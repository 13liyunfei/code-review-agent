package com.codereview.kit.extension.spi;

import com.codereview.kit.extension.ExtensionPoint;

import java.util.Map;

/**
 * 工作流阶段钩子，kit 扩展点之一。
 *
 * <p>使用方实现后在流水线关键阶段收到回调（追踪 / 轨迹记录 / 降级 / 审计），
 * 不阻塞主流程（回调异常自动忽略）。
 */
public interface StageHook extends ExtensionPoint {

    /** 阶段回调。stage 如 plan.created / task.completed / review.finished。 */
    void onStage(String stage, Map<String, Object> ctx);
}

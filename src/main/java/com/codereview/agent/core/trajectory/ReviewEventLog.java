package com.codereview.agent.core.trajectory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 事件源审查日志（append-only，不可变）。
 *
 * <p>对齐 deepseek-harness 的 {@code Session.log}（append 后 {@code deepFreeze}）：
 * 日志一旦写入即不可更改，{@link #append(ReviewEvent)} 返回<b>新的</b>不可变日志，
 * 而非原地修改，从而天然支持并发快照与重放，且杜绝「审查意见凭空出现、无法追责」。
 *
 * <p>不变量（关键）：
 * <ul>
 *   <li><b>任何写入审查 LLM 的内容必须先登记事件</b>——"Model-visible means logged"；</li>
 *   <li>事件不可变：外部无法修改已追加的事件；</li>
 *   <li>空值拒绝：{@link ReviewEvent} 构造即对类型/时间戳做前置校验。</li>
 * </ul>
 */
public final class ReviewEventLog {

    /** 不可变的事件序列（快照语义）。 */
    private final List<ReviewEvent> events;

    private ReviewEventLog(List<ReviewEvent> events) {
        // 防御性拷贝 + 包装为不可修改列表，保证 deepFreeze 语义
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    /** 创建空日志。 */
    public static ReviewEventLog empty() {
        return new ReviewEventLog(List.of());
    }

    /**
     * 追加一条事件，返回新的不可变日志（原日志不受影响）。
     *
     * @param event 待追加事件（非 null）
     * @return 含该事件的新日志
     * @throws IllegalArgumentException event 为 null
     */
    public ReviewEventLog append(ReviewEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("不能追加 null 事件");
        }
        List<ReviewEvent> next = new ArrayList<>(events);
        next.add(event);
        return new ReviewEventLog(next);
    }

    /**
     * 返回事件序列的不可修改视图（防止外部篡改）。
     *
     * @return 事件列表
     */
    public List<ReviewEvent> events() {
        return events;
    }

    /** 当前事件总数。 */
    public int size() {
        return events.size();
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return events.isEmpty();
    }
}

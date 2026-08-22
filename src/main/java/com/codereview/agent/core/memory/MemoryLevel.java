package com.codereview.agent.core.memory;

/**
 * 记忆层级（见文档“三层记忆架构”）。
 */
public enum MemoryLevel {

    /** 短期记忆：单 PR 内共享上下文。 */
    SHORT_TERM,
    /** 长期记忆：跨 PR 积累的经验与知识。 */
    LONG_TERM
}

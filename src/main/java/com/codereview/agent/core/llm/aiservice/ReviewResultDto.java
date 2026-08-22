package com.codereview.agent.core.llm.aiservice;

import java.util.List;

/**
 * LLM 结构化输出：一次审查的结果（发现列表）。
 *
 * <p>AiServices 的返回类型必须是单个对象，故用 record 包裹 List。
 */
public record ReviewResultDto(List<ReviewFindingDto> findings) {
}

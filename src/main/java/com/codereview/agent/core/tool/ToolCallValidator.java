package com.codereview.agent.core.tool;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 非法工具调用异常：当 LLM 请求了白名单之外的工具时抛出。
 */
class IllegalToolException extends RuntimeException {
    IllegalToolException(String message) {
        super(message);
    }
}

/**
 * 工具调用校验器（见文档“校验工具调用合法性”）。
 *
 * <p>在 LLM 返回工具调用后，校验其是否在 {@link ToolRouter} 下放的白名单内，
 * 防止模型越权调用未授权工具。
 */
public class ToolCallValidator {

    /**
     * 校验工具调用是否全部位于允许集合内。
     *
     * @param requestedToolNames 模型请求的工具名列表
     * @param allowed            允许的工具定义列表
     * @throws IllegalToolException 若存在未授权工具调用
     */
    public void validate(List<String> requestedToolNames, List<ToolDefinition> allowed) {
        Set<String> allowedNames = allowed.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
        for (String name : requestedToolNames) {
            if (!allowedNames.contains(name)) {
                throw new IllegalToolException("LLM 请求了未授权工具: " + name);
            }
        }
    }
}

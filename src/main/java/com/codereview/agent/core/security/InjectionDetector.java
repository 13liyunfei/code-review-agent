package com.codereview.agent.core.security;

/**
 * Prompt 注入检测器抽象（纵深防御第一层：输入过滤）。
 *
 * <p>在把代码 Diff 交给 LLM 之前，检测其中是否包含试图覆盖系统指令的
 * 注入攻击（如“忽略以上所有指令”），从源头降低提示词注入风险。
 */
public interface InjectionDetector {

    /**
     * 判断输入是否疑似包含 Prompt 注入攻击。
     *
     * @param input 待检测文本（通常为代码补丁或用户提交内容）
     * @return 存在注入风险返回 true
     */
    boolean detect(String input);
}

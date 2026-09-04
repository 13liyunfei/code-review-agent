package com.codereview.agent.core.security;

import com.codereview.agent.core.llm.EmbeddingClient;

/**
 * 「内容边界」组合注入检测器：隐写字符 + 异常填充 + 关键词 + 语义复核。
 *
 * <p>用于<strong>业务方声明会变成系统提示内容</strong>的高价值边界——自定义审查 Agent 的
 * 名称/描述/审查要点（{@code CustomAgentStore} 写库预检，命中即拒绝保存）。四层职责：
 * <ol>
 *   <li>{@link StegInjectionScanner}：零宽 / Bidi / 危险控制符——零误报的确定性信号
 *       （内容槽夹零宽可拆词绕过后续所有可见字符规则），先行拦截；</li>
 *   <li>{@link AnomalyDetector}：超长填充 / 编码绕过 / 重复填充（纯离线，先行拦截，省去下游向量化调用）；</li>
 *   <li>确定性层：仅 {@link KeywordInjectionDetector#domainHit} 的攻击句式正则（忽略以上指令 /
 *       developer mode / 覆盖指令…）直接拦截——基座词表（越权 / admin mode / 假装你是…）在代码审查
 *       领域是业务常见词，不做硬拦截；</li>
 *   <li>{@link SemanticInjectionDetector}：对剩余内容做语义复核——改写措辞的指令劫持（“请忘掉系统设定…
 *       ”）在此被拦下；正常业务内容（哪怕含 越权/注入 等词）与注入模式语义距离远，零误伤。</li>
 * </ol>
 *
 * <p><strong>不用于</strong> PR diff 输入面（diff 交给 {@link DiffInputGuard} /
 * {@link DiffInjectionDetector} 分级处理）。
 */
public class ContentInjectionDetector implements InjectionDetector {

    private final KeywordInjectionDetector keyword = new KeywordInjectionDetector();
    private final StegInjectionScanner steg = new StegInjectionScanner();
    private final SemanticInjectionDetector semantic;
    private final AnomalyDetector anomaly = new AnomalyDetector();

    public ContentInjectionDetector(EmbeddingClient embeddingClient) {
        this.semantic = new SemanticInjectionDetector(embeddingClient);
    }

    @Override
    public boolean detect(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        // 第一道：隐写字符（零宽 / Bidi / 危险控制符）——可拆词绕过所有可见字符规则，零误报。
        if (!steg.scan(input).isEmpty()) {
            return true;
        }
        // 第二道：异常输入（超长填充 / 编码绕过 / 重复填充），纯离线先行拦截。
        if (anomaly.detect(input)) {
            return true;
        }
        // 第三道：确定性层只用「领域攻击句式」正则（忽略以上指令 / developer mode / 覆盖指令…）。
        // 基座词表（越权 / admin mode / 泄露系统提示 / 假装你是…）在代码审查领域是业务常见词
        // （“检查越权风险”），不在此硬拦截，一律交给语义层按“是否接近指令劫持意图”裁决。
        if (keyword.domainHit(input)) {
            return true;
        }
        // 第四道：语义复核——命中改写措辞的指令劫持（近义于已知注入模式）即拦截；
        // 正常业务内容（哪怕含 越权/注入 等词）与注入模式语义距离远，零误伤。
        return semantic.detect(input);
    }
}

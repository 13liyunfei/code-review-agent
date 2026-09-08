package com.codereview.agent.core.analysis;

import java.util.List;

/**
 * SCA 漏洞数据源抽象：把「组件 → 已知漏洞」的查询与具体数据源解耦。
 *
 * <p>生产/开发/测试环境默认走 {@link OsvVulnSource}（OSV 真实漏洞库）；
 * {@link BuiltinVulnSource} 只是离线内置样本，仅作网络不可用时的兜底
 * 与确定性单测 fixture——**绝不允许把内置样本当作真实扫描结果**，
 * 报告会通过 {@link ScaScanner.ScaReport#sourceUsed()} 标注实际数据源。
 *
 * <p>失败语义：网络/解析失败由实现抛出异常，由上层（扫描器）决定
 * 降级策略，实现不得吞掉异常假装「无漏洞」——那正是本组件要根治的
 * 「查不了」伪装成「没有」的静默失效。
 */
public interface ScaVulnSource {

    /** 数据源标识：{@code osv} / {@code builtin}（写入报告与日志，用于诊断）。 */
    String id();

    /**
     * 批量查询组件漏洞。
     *
     * @param components 待查组件（来自 diff 依赖识别）
     * @return 命中漏洞（含组件引用）；无命中返回空列表，不抛异常
     * @throws Exception 整批查询失败（网络不通 / 响应不可解析等），交上层降级
     */
    List<ScaScanner.Vulnerability> lookupAll(List<ScaScanner.Component> components) throws Exception;
}

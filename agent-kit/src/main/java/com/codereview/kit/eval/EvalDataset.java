package com.codereview.kit.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 评估数据集（命名基准集）：一组带 ground-truth 的用例，用于回归评估 Agent 质量。
 */
public class EvalDataset {

    private final String name;
    private final List<EvalCase> cases = new ArrayList<>();

    public EvalDataset(String name) {
        this.name = name;
    }

    public EvalDataset add(EvalCase c) {
        cases.add(c);
        return this;
    }

    public String name() {
        return name;
    }

    public List<EvalCase> cases() {
        return List.copyOf(cases);
    }
}

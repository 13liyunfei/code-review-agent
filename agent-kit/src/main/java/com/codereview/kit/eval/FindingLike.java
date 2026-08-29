package com.codereview.kit.eval;

/**
 * 最小"发现"抽象（kit 评估组件不依赖任何审查域模型）。
 *
 * <p>使用方把自己的领域发现对象实现本接口即可接入 {@link LlmJudge}：
 * <pre>{@code
 * record Finding(String file, String ruleId, String title, int lineStart, String description)
 *         implements FindingLike { ... }
 * }</pre>
 */
public interface FindingLike {

    /** 文件路径（相对仓库根）。 */
    String file();

    /** 规则标识（如 LOGIC-002）。 */
    String ruleId();

    /** 标题。 */
    String title();

    /** 起始行号。 */
    int lineStart();

    /** 描述。 */
    String description();
}

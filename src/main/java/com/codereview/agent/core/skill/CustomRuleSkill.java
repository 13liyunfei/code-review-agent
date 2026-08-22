package com.codereview.agent.core.skill;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.skill.SkillMetadata;
import com.codereview.agent.core.skill.SkillResult;
import com.codereview.agent.core.util.DiffUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 由 {@link CustomRule} 驱动的团队自定义技能。
 *
 * <p>元信息 name 固定为 {@code custom:<id>}，便于注册表按名启停与去重。
 * 正则非法时退化为字面量匹配，保证提交即生效、不抛错。
 */
public class CustomRuleSkill implements Skill {

    private final CustomRule rule;
    private final SkillMetadata metadata;
    private final Pattern pattern;

    public CustomRuleSkill(CustomRule rule) {
        this.rule = rule;
        this.metadata = new SkillMetadata("custom:" + rule.id(), rule.description(), rule.category());
        Pattern p;
        try {
            p = Pattern.compile(rule.pattern());
        } catch (PatternSyntaxException e) {
            p = Pattern.compile(Pattern.quote(rule.pattern()));
        }
        this.pattern = p;
    }

    /** 规则 ID（与 {@link CustomRule#id()} 一致）。 */
    public String ruleId() {
        return rule.id();
    }

    @Override
    public SkillMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<SkillResult> execute(List<CodeDiff> diffs, ReviewContext ctx) {
        List<SkillResult> results = new ArrayList<>();
        Severity sev = parseSeverity(rule.severity());
        for (CodeDiff diff : diffs) {
            for (DiffUtils.Match m : DiffUtils.findPattern(diff.patch(), pattern)) {
                results.add(new SkillResult(diff.fileName(), m.lineNumber(), sev,
                        "CUSTOM-" + rule.id(), rule.title(), rule.description(),
                        rule.suggestion(), 0.9));
            }
        }
        return results;
    }

    private static Severity parseSeverity(String s) {
        if (s == null || s.isBlank()) {
            return Severity.MAJOR;
        }
        try {
            return Severity.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Severity.MAJOR;
        }
    }
}

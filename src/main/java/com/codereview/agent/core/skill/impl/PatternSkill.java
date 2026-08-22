package com.codereview.agent.core.skill.impl;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.skill.Skill;
import com.codereview.agent.core.skill.SkillMetadata;
import com.codereview.agent.core.skill.SkillResult;
import com.codereview.agent.core.util.DiffUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 通用正则型规则技能（SAST 风格的确定性扫描）。
 *
 * <p>把「一个正则 + 一段文案」封装成一条可插拔的审查技能，使新增规则零样板代码。
 * 既用于项目自带的内置规则（空 catch、SELECT *、超长行等），也是自定义技能的底层载体。
 */
public class PatternSkill implements Skill {

    private final SkillMetadata metadata;
    private final Pattern pattern;
    private final String ruleId;
    private final String title;
    private final String description;
    private final String suggestion;
    private final Severity severity;
    private final double confidence;

    public PatternSkill(String name, String category, String ruleId, String title,
                       String description, String suggestion, Severity severity,
                       double confidence, Pattern pattern) {
        this.metadata = new SkillMetadata(name, description, category);
        this.pattern = pattern;
        this.ruleId = ruleId;
        this.title = title;
        this.description = description;
        this.suggestion = suggestion;
        this.severity = severity;
        this.confidence = confidence;
    }

    @Override
    public SkillMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<SkillResult> execute(List<CodeDiff> diffs, ReviewContext ctx) {
        List<SkillResult> results = new ArrayList<>();
        for (CodeDiff diff : diffs) {
            for (DiffUtils.Match m : DiffUtils.findPattern(diff.patch(), pattern)) {
                results.add(new SkillResult(diff.fileName(), m.lineNumber(),
                        severity, ruleId, title, description, suggestion, confidence));
            }
        }
        return results;
    }
}

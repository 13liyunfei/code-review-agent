package com.codereview.agent.core.skill.impl;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.skill.Skill;
import com.codereview.agent.core.skill.SkillMetadata;
import com.codereview.agent.core.skill.SkillResult;
import com.codereview.agent.core.util.DiffUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 硬编码密钥检测 Skill（SAST 风格的确定性扫描）。
 *
 * <p>检测源码中疑似硬编码的密码、Token、API Key 等敏感信息，
 * 对应文档“不安全的密钥管理”风险点。
 */
public class HardcodedSecretSkill implements Skill {

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(password|passwd|secret|api[_-]?key|token|access[_-]?key)\\s*[:=]\\s*[\"']?[A-Za-z0-9+/=]{8,}");

    private final SkillMetadata metadata = new SkillMetadata(
            "hardcoded-secret", "检测硬编码的密码、Token、API Key 等敏感凭证", "security");

    @Override
    public SkillMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<SkillResult> execute(List<CodeDiff> diffs, ReviewContext ctx) {
        List<SkillResult> results = new ArrayList<>();
        for (CodeDiff diff : diffs) {
            for (DiffUtils.Match m : DiffUtils.findPattern(diff.patch(), SECRET_PATTERN)) {
                results.add(new SkillResult(
                        diff.fileName(), m.lineNumber(),
                        "SEC-002", "疑似硬编码敏感凭证",
                        "检测到代码中直接写入疑似密钥/密码的明文值，存在泄露风险。",
                        "请将凭证迁移至配置中心（如 Apollo）或密钥管理服务（KMS），运行时注入。"));
            }
        }
        return results;
    }
}

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
 * SQL 注入检测 Skill（SAST 风格的确定性扫描）。
 *
 * <p>检测将外部输入直接拼接进 SQL 语句的风险写法（字符串拼接），
 * 对应文档“注入类漏洞（SQL）”风险点。
 */
public class SqlInjectionSkill implements Skill {

    private static final Pattern SQL_CONCAT_PATTERN = Pattern.compile(
            "(?i)(select|insert|update|delete|from|where).*\\+.*(variables|params|request|input|args)");

    private final SkillMetadata metadata = new SkillMetadata(
            "sql-injection", "检测 SQL 语句中用户输入的直接字符串拼接", "security");

    @Override
    public SkillMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<SkillResult> execute(List<CodeDiff> diffs, ReviewContext ctx) {
        List<SkillResult> results = new ArrayList<>();
        for (CodeDiff diff : diffs) {
            for (DiffUtils.Match m : DiffUtils.findPattern(diff.patch(), SQL_CONCAT_PATTERN)) {
                results.add(new SkillResult(
                        diff.fileName(), m.lineNumber(),
                        "SEC-001", "SQL 注入风险",
                        "检测到将用户输入直接拼接进 SQL 语句，攻击者可借此篡改查询逻辑。",
                        "使用参数化查询 / 预编译语句（PreparedStatement），避免字符串拼接 SQL。"));
            }
        }
        return results;
    }
}

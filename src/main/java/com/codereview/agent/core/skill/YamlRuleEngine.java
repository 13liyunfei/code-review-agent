package com.codereview.agent.core.skill;

import com.codereview.agent.core.admin.dto.CustomRuleRequest;
import com.codereview.agent.core.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 低代码规则平台（YAML 驱动）。
 *
 * <p>安全 / 合规团队无需编写 Java，只需维护一份 YAML 规则清单即可把团队规范注入审查引擎：
 * <pre>
 * rules:
 *   - name: 禁止提交 TODO
 *     category: style
 *     severity: MINOR
 *     pattern: '(?i)//.*\b(todo|fixme)\b'
 *     title: 遗留 TODO 标记
 *     description: 提交中仍包含待办标记
 *     suggestion: 登记到任务系统并移除
 * </pre>
 *
 * <p>解析后逐条转换为 {@link CustomRule} 并通过 {@link SkillRegistry} 即时生效（含持久化）。
 */
@Component
public class YamlRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(YamlRuleEngine.class);

    private final SkillRegistry registry;

    public YamlRuleEngine(SkillRegistry registry) {
        this.registry = registry;
    }

    /** 导入结果。 */
    public record ImportResult(int imported, List<String> errors) {
    }

    /**
     * 解析 YAML 规则文本并批量导入到指定团队。
     *
     * @param teamId   目标团队标识
     * @param yamlText YAML 文本（含 rules 列表）
     * @return 导入条数与错误信息
     */
    @SuppressWarnings("unchecked")
    public ImportResult importYaml(String teamId, String yamlText) {
        List<String> errors = new ArrayList<>();
        int imported = 0;
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(yamlText);
            if (root == null || !root.containsKey("rules")) {
                return new ImportResult(0, List.of("YAML 缺少顶层 rules 列表"));
            }
            Object rulesObj = root.get("rules");
            if (!(rulesObj instanceof List<?>)) {
                return new ImportResult(0, List.of("rules 必须是列表"));
            }
            for (Object o : (List<Object>) rulesObj) {
                if (!(o instanceof Map<?, ?>)) {
                    errors.add("规则项不是映射：" + o);
                    continue;
                }
                try {
                    CustomRuleRequest req = toRequest((Map<String, Object>) o);
                    registry.addCustomRule(teamId, req);
                    imported++;
                } catch (Exception e) {
                    errors.add("规则解析失败：" + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[YAML规则] 解析失败：{}", e.getMessage());
            errors.add("YAML 语法错误：" + e.getMessage());
        }
        log.info("[YAML规则] 团队 {} 导入 {} 条，失败 {} 条", teamId, imported, errors.size());
        return new ImportResult(imported, errors);
    }

    @SuppressWarnings("unchecked")
    private CustomRuleRequest toRequest(Map<String, Object> m) {
        String name = str(m, "name", "");
        String category = str(m, "category", "custom");
        String severity = str(m, "severity", "MAJOR");
        String pattern = str(m, "pattern", "");
        String title = str(m, "title", name);
        String description = str(m, "description", "");
        String suggestion = str(m, "suggestion", "");
        if (name.isBlank() || pattern.isBlank()) {
            throw new IllegalArgumentException("name 与 pattern 不能为空");
        }
        Severity sev = safeSeverity(severity);
        return new CustomRuleRequest(name, category, sev.name(), pattern, title, description, suggestion);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v).trim();
    }

    private static Severity safeSeverity(String s) {
        try {
            return Severity.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Severity.MAJOR;
        }
    }
}

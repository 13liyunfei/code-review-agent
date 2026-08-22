package com.codereview.agent.core.admin;

import com.codereview.agent.core.admin.dto.CustomRuleRequest;
import com.codereview.agent.core.admin.dto.SkillInfo;
import com.codereview.agent.core.skill.SkillRegistry;
import com.codereview.agent.core.skill.YamlRuleEngine;
import com.codereview.agent.tenant.Teams;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 技能管理控制器（Skills 市场后端）。
 *
 * <p>提供技能列表、启停开关、团队自定义规则（JSON 单条 / YAML 批量）的增删。前端控制台通过
 * 控制台微服务（微服务调用）访问本接口。所有操作按 {@code X-Team-Id} 头（或 {@code team} 参数）隔离。
 */
@RestController
@RequestMapping("/api/admin/skills")
public class SkillAdminController {

    private final SkillRegistry registry;
    private final YamlRuleEngine yamlRuleEngine;

    public SkillAdminController(SkillRegistry registry, YamlRuleEngine yamlRuleEngine) {
        this.registry = registry;
        this.yamlRuleEngine = yamlRuleEngine;
    }

    /** 列出某团队全部技能（内置 + 自定义）及启用状态。 */
    @GetMapping
    public List<SkillInfo> list(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                               @RequestParam(value = "team", required = false) String teamParam) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        return registry.listSkills(teamId);
    }

    /** 启停某团队的某技能。 */
    @PostMapping("/{name}/toggle")
    public Map<String, Object> toggle(@PathVariable String name,
                                     @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                     @RequestParam(value = "team", required = false) String teamParam,
                                     @RequestBody(required = false) Map<String, Object> body) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        boolean enabled = body == null || Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("enabled", true)));
        registry.setEnabled(teamId, name, enabled);
        return Map.of("team", teamId, "name", name, "enabled", enabled);
    }

    /** 新增某团队的自定义规则（单条，JSON）。 */
    @PostMapping("/custom")
    public SkillInfo addCustom(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                              @RequestParam(value = "team", required = false) String teamParam,
                              @RequestBody CustomRuleRequest req) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        return registry.addCustomRule(teamId, req);
    }

    /** 删除某团队的自定义规则。 */
    @DeleteMapping("/custom/{id}")
    public Map<String, Object> removeCustom(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                           @RequestParam(value = "team", required = false) String teamParam,
                                           @PathVariable String id) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        registry.removeCustomRule(teamId, id);
        return Map.of("team", teamId, "id", id, "deleted", true);
    }

    /**
     * 批量导入某团队规则（YAML 低代码规则平台）。
     *
     * <p>请求体为 YAML 文本（含 rules 列表），安全 / 合规团队无需编写 Java 即可注入规范。
     */
    @PostMapping("/yaml")
    public Map<String, Object> importYaml(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                         @RequestParam(value = "team", required = false) String teamParam,
                                         @RequestBody String yaml) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        YamlRuleEngine.ImportResult result = yamlRuleEngine.importYaml(teamId, yaml);
        return Map.of("team", teamId, "imported", result.imported(), "errors", result.errors());
    }
}

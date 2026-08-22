package com.codereview.agent.core.admin;

import com.codereview.agent.core.admin.dto.KnowledgeInfo;
import com.codereview.agent.core.admin.dto.SkillInfo;
import com.codereview.agent.core.skill.SkillRegistry;
import com.codereview.agent.tenant.Teams;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 控制台仪表盘统计接口（聚合技能与知识概况）。按团队隔离。
 */
@RestController
@RequestMapping("/api/admin/stats")
public class StatsController {

    private final SkillRegistry registry;
    private final KnowledgeIngestionService ingestion;

    public StatsController(SkillRegistry registry, KnowledgeIngestionService ingestion) {
        this.registry = registry;
        this.ingestion = ingestion;
    }

    @GetMapping
    public Map<String, Object> stats(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                    @RequestParam(value = "team", required = false) String teamParam) throws Exception {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        List<SkillInfo> skills = registry.listSkills(teamId);
        List<KnowledgeInfo> knowledge = ingestion.list(teamId);
        long enabled = skills.stream().filter(SkillInfo::enabled).count();
        long custom = skills.stream().filter(SkillInfo::custom).count();
        long indexed = knowledge.stream().filter(KnowledgeInfo::indexed).count();
        return Map.of(
                "team", teamId,
                "skillTotal", skills.size(),
                "skillEnabled", enabled,
                "skillCustom", custom,
                "knowledgeTotal", knowledge.size(),
                "knowledgeIndexed", indexed);
    }
}

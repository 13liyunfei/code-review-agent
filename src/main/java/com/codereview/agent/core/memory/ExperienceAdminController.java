package com.codereview.agent.core.memory;

import com.codereview.agent.tenant.Teams;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 经验库治理端点（记忆升级与遗忘的管理面）。
 *
 * <p>审计：列出某团队全部经验条目（含归档，可看证据计数与命中次数）；
 * 治理：手动归档（遗忘）或物理删除。运维可据此把误报沉淀的错误经验移出检索面，
 * 或清空某个长期错误的 pattern。按 {@code X-Team-Id} 头（或 {@code team} 参数）隔离。
 */
@RestController
@RequestMapping("/api/admin/memory/experiences")
public class ExperienceAdminController {

    private final ExperienceStore store;

    public ExperienceAdminController(ExperienceStore store) {
        this.store = store;
    }

    /** 经验审计列表。 */
    @GetMapping
    public List<ExperienceEntry> list(
            @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
            @RequestParam(value = "team", required = false) String teamParam,
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        return includeArchived ? store.listAll(teamId)
                : store.listAll(teamId).stream().filter(ExperienceEntry::retrievable).toList();
    }

    /** 手动遗忘（软删）：条目移出检索面。 */
    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(
            @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
            @RequestParam(value = "team", required = false) String teamParam,
            @PathVariable long id) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        boolean ok = store.archive(teamId, id);
        return Map.of("team", teamId, "id", id, "archived", ok);
    }

    /** 手动物理删除。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> purge(
            @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
            @RequestParam(value = "team", required = false) String teamParam,
            @PathVariable long id) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        boolean ok = store.purge(teamId, id);
        return Map.of("team", teamId, "id", id, "deleted", ok);
    }
}

package com.codereview.agent.core.admin;

import com.codereview.agent.core.admin.dto.CustomAgentRequest;
import com.codereview.agent.tenant.Teams;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自定义审查 Agent 管理控制器（后管「自定义 Agent 列表」后端）。
 *
 * <p>提供列表 / 新增 / 编辑 / 删除 / 启停，所有操作按 {@code X-Team-Id} 头（或 {@code team} 参数）
 * 隔离，写库前对业务方提交内容做 {@link com.codereview.agent.core.security.InjectionDetector}
 * 预检（命中即拒绝），防止业务方自己写入越权提示。
 */
@RestController
@RequestMapping("/api/admin/agents")
public class AgentAdminController {

    private final CustomAgentStore store;

    public AgentAdminController(CustomAgentStore store) {
        this.store = store;
    }

    /** 自定义 Agent 列表（含启用态，按团队隔离）。 */
    @GetMapping
    public List<CustomAgentDef> list(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                     @RequestParam(value = "team", required = false) String teamParam) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        return store.list(teamId);
    }

    /** 新增自定义 Agent（含注入预检）。 */
    @PostMapping
    public CustomAgentDef add(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                              @RequestParam(value = "team", required = false) String teamParam,
                              @RequestBody CustomAgentRequest req) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        return store.add(teamId, req.name(), req.description(),
                req.focusPoints(), req.severityBias());
    }

    /** 编辑更新（乐观锁，含注入预检）。 */
    @PutMapping("/{id}")
    public CustomAgentDef update(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                 @RequestParam(value = "team", required = false) String teamParam,
                                 @PathVariable String id,
                                 @RequestBody CustomAgentRequest req) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        long version = req.version() == null ? 0L : req.version();
        boolean enabled = req.enabled() == null || req.enabled();
        return store.update(teamId, id, req.name(), req.description(),
                req.focusPoints(), req.severityBias(), enabled, version);
    }

    /** 删除自定义 Agent。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                      @RequestParam(value = "team", required = false) String teamParam,
                                      @PathVariable String id) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        store.remove(teamId, id);
        return Map.of("team", teamId, "id", id, "deleted", true);
    }

    /** 启停自定义 Agent。 */
    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                      @RequestParam(value = "team", required = false) String teamParam,
                                      @PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        boolean enabled = body == null || Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("enabled", true)));
        store.setEnabled(teamId, id, enabled);
        return Map.of("team", teamId, "id", id, "enabled", enabled);
    }

    /** 业务方提交内容命中注入风险或参数非法：返回 400 并附带明确原因（而非 500）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "bad_request", "message", ex.getMessage()));
    }

    /** 乐观锁版本冲突：返回 409。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> onIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "conflict", "message", ex.getMessage()));
    }
}

package com.codereview.agent.ide;

import com.codereview.agent.core.analysis.AstAnalyzer;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.skill.Skill;
import com.codereview.agent.core.skill.SkillResult;
import com.codereview.agent.core.skill.impl.PatternSkill;
import com.codereview.agent.core.util.DiffUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * IDE LSP 接口（Language Server Protocol，最小可用实现）。
 *
 * <p>以独立进程形式运行（JSON-RPC 2.0 over stdio，遵循 LSP 的 Content-Length 分帧），
 * 让 VSCode / IDEA 等编辑器实时获得审查诊断与「快速修复」入口：
 * <ul>
 *   <li>{@code textDocument/didOpen} / {@code textDocument/didChange} → 触发分析；</li>
 *   <li>{@code textDocument/publishDiagnostics} → 推送红波浪线（AST 结构 + 内置规则）；</li>
 *   <li>{@code textDocument/codeAction} → 提供「查看修复建议」Quick Fix。</li>
 * </ul>
 *
 * <p>复用引擎的 {@link AstAnalyzer} 与 {@link PatternSkill} 规则集，保证 IDE 与 CI 审查口径一致。
 * 启动后由编辑器通过 LSP 客户端接入（详见 README 的 VSCode 扩展接线说明）。
 */
public final class IdeReviewServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 内置规则集（与引擎 ReviewAgentConfig.allSkills 保持一致）。 */
    private static final List<Skill> RULES = List.of(
            new PatternSkill("hardcoded-secret", "security", "SEC-001", "疑似硬编码密钥",
                    "密钥不应硬编码于源码。", "迁移到配置中心/环境变量。", Severity.MAJOR, 0.9,
                    Pattern.compile("(?i)(password|secret|api[_-]?key|token)\\s*=\\s*[\"'][^\"']{6,}[\"']")),
            new PatternSkill("print-stack-trace", "logic", "LOGIC-002", "直接打印异常堆栈",
                    "绕过统一日志框架。", "使用日志框架记录。", Severity.MAJOR, 0.95,
                    Pattern.compile("e\\.printStackTrace\\s*\\(\\s*\\)")),
            new PatternSkill("system-out", "logic", "LOGIC-003", "使用 System.out 输出",
                    "生产代码应避免标准输出。", "替换为日志框架。", Severity.MAJOR, 0.95,
                    Pattern.compile("System\\.out\\.print")),
            new PatternSkill("select-star", "performance", "PERF-001", "避免使用 SELECT *",
                    "全字段查询增加开销。", "显式列出字段。", Severity.MAJOR, 0.95,
                    Pattern.compile("(?i)select\\s+\\*\\s+from")),
            new PatternSkill("new-thread", "architecture", "ARCH-001", "直接 new Thread 创建线程",
                    "散落创建线程难管控。", "使用统一线程池。", Severity.MAJOR, 0.95,
                    Pattern.compile("\\bnew\\s+Thread\\s*\\("))
    );

    private final Map<String, String> docs = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        new IdeReviewServer().run(System.in, System.out);
    }

    /** 服务主循环（可在测试中注入流）。 */
    public void run(InputStream in, OutputStream out) throws IOException {
        BufferedInputStream bis = new BufferedInputStream(in);
        BufferedOutputStream bos = new BufferedOutputStream(out);
        StringBuilder headerBuf = new StringBuilder();
        while (true) {
            // 读取 Content-Length 头
            int contentLength = -1;
            headerBuf.setLength(0);
            int c;
            boolean headersDone = false;
            while ((c = bis.read()) != -1) {
                if (c == '\r') {
                    continue;
                }
                if (c == '\n') {
                    String line = headerBuf.toString();
                    headerBuf.setLength(0);
                    if (line.isEmpty()) {
                        headersDone = true;
                        break;
                    }
                    if (line.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                } else {
                    headerBuf.append((char) c);
                }
            }
            if (c == -1) {
                break; // EOF
            }
            if (!headersDone || contentLength <= 0) {
                continue;
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = bis.read(body, read, contentLength - read);
                if (n == -1) {
                    break;
                }
                read += n;
            }
            String msg = new String(body, 0, read, StandardCharsets.UTF_8);
            JsonNode req = MAPPER.readTree(msg);
            String method = req.path("method").asText("");
            JsonNode id = req.get("id");
            ObjectNode response = handle(method, req, bos);
            if (response != null) {
                if (id != null && !id.isNull()) {
                    response.put("id", id);
                }
                send(bos, response);
            }
        }
        bos.flush();
    }

    private ObjectNode handle(String method, JsonNode req, OutputStream out) {
        switch (method) {
            case "initialize":
                return initialize(req);
            case "initialized":
            case "exit":
                return null;
            case "textDocument/didOpen":
            case "textDocument/didChange": {
                JsonNode doc = req.path("params").path("textDocument");
                String uri = doc.path("uri").asText("");
                String text = extractText(req);
                docs.put(uri, text);
                publish(uri, text, out);
                return null; // 通知无需响应
            }
            case "textDocument/codeAction":
                return codeAction(req);
            default:
                // 未知请求：返回方法未找到（若有 id）
                if (req.get("id") != null) {
                    ObjectNode err = MAPPER.createObjectNode();
                    err.put("jsonrpc", "2.0");
                    err.putObject("error").put("code", -32601).put("message", "Method not found: " + method);
                    return err;
                }
                return null;
        }
    }

    private ObjectNode initialize(JsonNode req) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        ObjectNode caps = r.putObject("result").putObject("capabilities");
        caps.putObject("textDocumentSync").put("openClose", true).put("change", 1);
        ObjectNode codeAction = caps.putObject("codeActionProvider");
        codeAction.putArray("codeActionKinds").add("quickfix");
        return r;
    }

    /** 分析文档并发布诊断。 */
    private void publish(String uri, String text, OutputStream out) {
        List<ObjectNode> diags = new ArrayList<>();
        // 1. 内置规则（基于全量文档构造伪 diff）
        String fakePatch = toFakePatch(text);
        CodeDiff cd = new CodeDiff(uri, fakePatch, "java", 0, 0);
        for (Skill s : RULES) {
            for (SkillResult sr : s.execute(List.of(cd), null)) {
                diags.add(diag(sr.lineStart(), sr.severity(), sr.title(), sr.suggestion(), sr.ruleId()));
            }
        }
        // 2. AST 结构（方法过长）
        AstAnalyzer.AstReport report = AstAnalyzer.analyze(text, uri);
        for (AstAnalyzer.ClassInfo ci : report.classes()) {
            for (AstAnalyzer.MethodInfo m : ci.methods()) {
                if (m.length() > 60) {
                    diags.add(diag(m.startLine(), Severity.MAJOR, "方法体过长(" + m.length() + "行)",
                            "拆分为更小的职责单一方法。", "AST-LONG-METHOD"));
                }
            }
        }
        // 推送
        ObjectNode notify = MAPPER.createObjectNode();
        notify.put("jsonrpc", "2.0");
        notify.put("method", "textDocument/publishDiagnostics");
        ObjectNode params = notify.putObject("params");
        params.put("uri", uri);
        ArrayNode arr = params.putArray("diagnostics");
        diags.forEach(arr::add);
        try {
            send(out, notify);
        } catch (IOException ignored) {
            // 后台推送失败不影响主流程
        }
    }

    private ObjectNode codeAction(JsonNode req) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        ArrayNode actions = r.putArray("result");
        // 提供「查看修复建议」快速修复（演示：打开命令面板提示）
        ObjectNode action = actions.addObject();
        action.put("title", "查看代码审查修复建议");
        action.put("kind", "quickfix");
        ObjectNode cmd = action.putObject("command");
        cmd.put("title", "查看代码审查修复建议");
        cmd.put("command", "codeReviewAgent.showFix");
        return r;
    }

    private ObjectNode diag(int line, Severity sev, String msg, String suggestion, String code) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("severity", severityCode(sev)); // 1 Error, 2 Warning, 3 Info, 4 Hint
        d.put("source", "code-review-agent");
        d.put("code", code);
        d.put("message", msg + " " + suggestion);
        ObjectNode range = d.putObject("range");
        ObjectNode start = range.putObject("start");
        ObjectNode end = range.putObject("end");
        start.put("line", Math.max(0, line - 1)).put("character", 0);
        end.put("line", Math.max(0, line - 1)).put("character", 200);
        return d;
    }

    private static int severityCode(Severity s) {
        return switch (s) {
            case BLOCKER, MAJOR -> 2;   // Warning
            case MINOR -> 3;            // Info
            default -> 4;               // Hint
        };
    }

    /** 将完整文档文本转为「全上下文」伪 diff，使 DiffUtils 行号从 1 开始映射。 */
    private static String toFakePatch(String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/f b/f\n--- a/f\n+++ b/f\n");
        String[] lines = text.split("\n", -1);
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String l : lines) {
            sb.append(' ').append(l).append('\n');
        }
        return sb.toString();
    }

    private static String extractText(JsonNode req) {
        JsonNode params = req.path("params");
        JsonNode content = params.path("contentChanges").path(0).path("text");
        if (!content.isMissingNode()) {
            return content.asText("");
        }
        return params.path("textDocument").path("text").asText("");
    }

    private static void send(OutputStream os, ObjectNode msg) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(msg);
        String header = "Content-Length: " + bytes.length + "\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(bytes);
        os.flush();
    }
}

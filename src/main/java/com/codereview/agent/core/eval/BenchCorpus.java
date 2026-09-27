package com.codereview.agent.core.eval;

import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 基准语料加载器：从 classpath 的 {@code bench/} 读语料（零外部依赖、零网络）。
 *
 * <p>为什么语料放在 resources 而不是测试资源：**它同时被「测试」和「可复跑的离线评测」
 * 使用**，而且 CI 门禁要在打包阶段就能读到它。放在 {@code src/main/resources} 还带来一个
 * 副产物——语料会跟着 jar 一起发布，任何人都能对着它复核指标，不需要跑本仓的采集脚本。
 *
 * <p>语料由 {@code tools/bench/collect_corpus.py} 与 {@code label_assist.py} 生成，
 * 两个脚本都不在构建链上（采集要联网 / 要人工裁决），所以这里只做**只读加载 + 校验**：
 * manifest 里记的 {@code patchSha256} 与 case 文件里的必须一致，否则说明有人手改了语料。
 */
public final class BenchCorpus {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "bench/";

    private final String codeReviewAgentHead;
    private final List<BenchCase> cases;

    private BenchCorpus(String head, List<BenchCase> cases) {
        this.codeReviewAgentHead = head;
        this.cases = List.copyOf(cases);
    }

    /** 从默认 classpath 位置加载。 */
    public static BenchCorpus load() {
        return load(BenchCorpus.class.getClassLoader());
    }

    /** 从指定 classloader 加载（测试用）。 */
    public static BenchCorpus load(ClassLoader cl) {
        JsonNode manifest = read(cl, BASE + "manifest.json");
        String head = manifest.path("codeReviewAgentHead").asText("?");
        List<BenchCase> out = new ArrayList<>();
        for (JsonNode m : manifest.path("cases")) {
            String id = m.path("id").asText();
            JsonNode c = read(cl, BASE + "cases/" + id + ".json");
            String declared = m.path("patchSha256").asText();
            if (!declared.equals(c.path("patchSha256").asText())) {
                throw new IllegalStateException("语料完整性校验失败：" + id
                        + " 的 patchSha256 与 manifest 不一致（语料被手改过？请重跑 collect_corpus.py）");
            }
            out.add(toCase(c));
        }
        return new BenchCorpus(head, out);
    }

    private static JsonNode read(ClassLoader cl, String path) {
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到语料文件：" + path
                        + "（是否漏了 src/main/resources/bench/？）");
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("语料读取失败：" + path, e);
        }
    }

    private static BenchCase toCase(JsonNode c) {
        List<BenchGroundTruth> gt = new ArrayList<>();
        for (JsonNode g : c.path("groundTruth")) {
            gt.add(new BenchGroundTruth(
                    g.path("ruleId").asText(""),
                    g.path("category").asText("logic"),
                    severity(g.path("severity").asText("MAJOR")),
                    g.path("file").asText(""),
                    g.path("lineStart").asInt(0),
                    g.path("lineEnd").asInt(g.path("lineStart").asInt(0)),
                    g.path("note").asText(""),
                    g.path("labelSource").asText("manual")));
        }
        JsonNode ri = c.path("replayInput");
        List<Finding> findings = new ArrayList<>();
        if (ri.has("agents")) {
            for (JsonNode ag : ri.path("agents")) {
                AgentType at = agentType(ag.path("agentType").asText("LOGIC"));
                for (JsonNode f : ag.path("findings")) {
                    findings.add(new Finding(
                            at,
                            f.path("file").asText(""),
                            f.path("lineStart").asInt(0),
                            f.path("lineEnd").asInt(f.path("lineStart").asInt(0)),
                            severity(f.path("severity").asText("MAJOR")),
                            f.path("category").asText("logic"),
                            f.path("ruleId").asText(""),
                            f.path("title").asText(""),
                            f.path("description").asText(""),
                            f.path("suggestion").asText(""),
                            f.path("confidence").asDouble(0.75),
                            f.path("source").asText("LLM")));
                }
            }
        }
        List<ReviewFeedback> fb = new ArrayList<>();
        for (JsonNode f : ri.path("feedback")) {
            String agent = f.path("agentType").isNull() ? null : f.path("agentType").asText(null);
            fb.add(new ReviewFeedback(f.path("ruleId").asText(""), agent,
                    f.path("isFalsePositive").asBoolean(true), f.path("note").asText(""),
                    f.path("file").asText(null)));
        }
        return new BenchCase(
                c.path("id").asText(""),
                c.path("repoKey").asText(""),
                c.path("provenance").asText(""),
                c.path("shaShort").asText(""),
                c.path("subject").asText(""),
                c.path("changedFileCount").asInt(0),
                c.path("addedLines").asInt(0),
                c.path("deletedLines").asInt(0),
                c.path("patchSha256").asText(""),
                gt,
                new BenchCase.ReplayInput(ri.path("origin").asText("none"),
                        ri.path("note").asText(""), findings, fb));
    }

    private static Severity severity(String s) {
        try {
            return Severity.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Severity.MAJOR;
        }
    }

    private static AgentType agentType(String s) {
        try {
            return AgentType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AgentType.LOGIC;
        }
    }

    /** 本仓采集时的 HEAD（写进基线报告，便于判断基线是否过期）。 */
    public String codeReviewAgentHead() {
        return codeReviewAgentHead;
    }

    public List<BenchCase> cases() {
        return cases;
    }

    /** 可参与算分的 case（有回放输入的那些）。 */
    public List<BenchCase> scorableCases() {
        return cases.stream().filter(c -> c.replayInput().scorable()).toList();
    }

    /** ground-truth 总条数。 */
    public int groundTruthCount() {
        return cases.stream().mapToInt(c -> c.groundTruth().size()).sum();
    }
}

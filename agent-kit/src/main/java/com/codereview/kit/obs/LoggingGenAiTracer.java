package com.codereview.kit.obs;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志式 tracer：JSON 行输出（stdout），并保留在内存供断言/导出。
 */
public class LoggingGenAiTracer implements GenAiTracer {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<GenAiSpan> spans = new ArrayList<>();

    @Override
    public synchronized void record(GenAiSpan span) {
        spans.add(span);
        try {
            System.out.println("[genai] " + mapper.writeValueAsString(span));
        } catch (Exception ignored) {
        }
    }

    /** 已记录 span（供测试断言 / 导出）。 */
    public synchronized List<GenAiSpan> spans() {
        return List.copyOf(spans);
    }

    public synchronized void reset() {
        spans.clear();
    }
}

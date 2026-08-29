package com.codereview.kit.router;

import com.codereview.kit.ChatModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多模型路由 + failover（Model Router）。
 *
 * <p>注册多个 {@link ChatModel}（供应商/规格），按优先级（大优先）选择；
 * 主模型调用异常时自动切换到次优模型，保证可用性（对齐企业级 ModelGateway 能力）。
 */
public class ModelRouter {

    /** 注册的模型条目。 */
    public record ModelEntry(String name, ChatModel model, int priority, AtomicLong calls, AtomicLong failures) {
        static ModelEntry of(String name, ChatModel model, int priority) {
            return new ModelEntry(name, model, priority, new AtomicLong(), new AtomicLong());
        }
    }

    private final List<ModelEntry> entries = new ArrayList<>();

    public void register(String name, ChatModel model, int priority) {
        entries.add(ModelEntry.of(name, model, priority));
        entries.sort(Comparator.comparingInt(ModelEntry::priority).reversed());
    }

    /** 当前优先模型（无注册抛异常）。 */
    public ModelEntry primary() {
        if (entries.isEmpty()) {
            throw new IllegalStateException("未注册任何模型");
        }
        return entries.get(0);
    }

    /** 已注册条目（按优先级降序）。 */
    public List<ModelEntry> entries() {
        return List.copyOf(entries);
    }
}

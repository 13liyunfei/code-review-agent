package com.codereview.kit.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 扩展注册中心：按扩展点类型组织实现，按 {@code order()} 升序返回织入链。
 *
 * <p>线程安全；同名覆盖告警。运行期注册即生效（积木架构「标准之上叠加自定义」的落点）。
 */
public class ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    private final Map<Class<? extends ExtensionPoint>, List<ExtensionPoint>> byType = new ConcurrentHashMap<>();

    public <T extends ExtensionPoint> void register(Class<T> type, T ext) {
        List<ExtensionPoint> list = byType.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        boolean replaced = list.removeIf(e -> e.name().equals(ext.name()));
        list.add(ext);
        list.sort(Comparator.comparingInt(ExtensionPoint::order));
        log.info("[ExtensionRegistry] {} 注册扩展 {}（order={}）{}", type.getSimpleName(),
                ext.name(), ext.order(), replaced ? "（覆盖同名）" : "");
    }

    /** 返回某扩展点的织入链（order 升序，不可变视图）。 */
    @SuppressWarnings("unchecked")
    public <T extends ExtensionPoint> List<T> list(Class<T> type) {
        List<ExtensionPoint> list = byType.get(type);
        if (list == null) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (ExtensionPoint e : list) {
            if (type.isInstance(e)) {
                out.add((T) e);
            }
        }
        return List.copyOf(out);
    }

    public int total() {
        return byType.values().stream().mapToInt(List::size).sum();
    }
}

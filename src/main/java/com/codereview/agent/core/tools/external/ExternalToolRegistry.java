package com.codereview.agent.core.tools.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部工具注册表（SPI 注册 / 按名调用）。
 *
 * <p>维护 {@link ExternalToolProvider} 集合，审查链路可通过 {@link #find} / {@link #invoke}
 * 按名调用外部能力；同一工具名冲突时以最近注册者为准（可覆盖）。注册表本身无第三方依赖，
 * MCP 等协议接入由具体 Provider 实现承载。
 */
@Component
public class ExternalToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExternalToolRegistry.class);

    /** providerName -> provider。 */
    private final ConcurrentHashMap<String, ExternalToolProvider> providers = new ConcurrentHashMap<>();
    /** 工具名 -> providerName（工具路由）。 */
    private final ConcurrentHashMap<String, String> toolIndex = new ConcurrentHashMap<>();

    /** 注册一个提供者（幂等：重复注册以新覆盖旧）。 */
    public void register(ExternalToolProvider provider) {
        if (provider == null) {
            return;
        }
        providers.put(provider.name(), provider);
        if (provider.capabilities() != null) {
            for (String t : provider.capabilities()) {
                toolIndex.put(t, provider.name());
            }
        }
        log.info("[ExternalTool] 注册提供者 {}（{} 个能力）", provider.name(),
                provider.capabilities() == null ? 0 : provider.capabilities().size());
    }

    /** 注销一个提供者。 */
    public void unregister(String providerName) {
        ExternalToolProvider removed = providers.remove(providerName);
        if (removed != null && removed.capabilities() != null) {
            for (String t : removed.capabilities()) {
                toolIndex.remove(t, providerName);
            }
        }
    }

    /** 按工具名查找所属提供者。 */
    public Optional<ExternalToolProvider> find(String tool) {
        String providerName = toolIndex.get(tool);
        return providerName == null ? Optional.empty() : Optional.ofNullable(providers.get(providerName));
    }

    /** 列出全部已注册提供者。 */
    public List<ExternalToolProvider> providers() {
        return new ArrayList<>(providers.values());
    }

    /**
     * 调用外部工具（fail-fast：工具未注册抛 {@link IllegalArgumentException}）。
     *
     * @param tool 工具名
     * @param args 参数
     * @return 工具结果
     */
    public String invoke(String tool, Map<String, Object> args) {
        ExternalToolProvider provider = find(tool)
                .orElseThrow(() -> new IllegalArgumentException("外部工具未注册：" + tool));
        try {
            return provider.invoke(tool, args == null ? Map.of() : args);
        } catch (Exception e) {
            throw new IllegalStateException("外部工具调用失败：" + tool + "（" + provider.name() + "）", e);
        }
    }
}

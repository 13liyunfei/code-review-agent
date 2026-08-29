package com.codereview.kit.extension.spi;

import com.codereview.kit.extension.ExtensionPoint;

import java.util.Optional;

/**
 * 记忆读写策略，kit 扩展点之一。
 *
 * <p>使用方实现后替换默认记忆实现（如换向量库 / 团队隔离 / 经验库叠加）。
 * 字符串级最小契约，便于任意存储接入。
 */
public interface MemoryStrategy extends ExtensionPoint {

    /** 读：按 key 取记忆（无则空）。 */
    Optional<String> get(String key);

    /** 写：存记忆（value 为 null 表示删除）。 */
    void put(String key, String value);
}

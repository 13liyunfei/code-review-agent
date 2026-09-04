package com.codereview.agent.core.analysis.index;

import java.util.List;
import java.util.Optional;

/**
 * 源码获取抽象——仓库索引的唯一数据来源。
 *
 * <p>抽成接口而非直接依赖 {@code GiteaApiClient}，有两个实际收益：
 * <ul>
 *   <li>索引构建可以脱离网络单测（用 Map 打桩即可覆盖跨文件调用图逻辑）；</li>
 *   <li>将来接 GitLab / GitHub 只需换实现，索引逻辑不动。</li>
 * </ul>
 *
 * <p>所有路径均为仓库内相对路径（如 {@code src/main/java/com/x/Foo.java}）。
 */
public interface SourceFetcher {

    /**
     * 取文件全量内容。
     *
     * @param path 仓库内相对路径
     * @return 文件全文；不存在或无权限时返回空
     */
    Optional<String> fetch(String path);

    /**
     * 列出目录下的条目（非递归）。
     *
     * @param dir 目录路径；仓库根目录传 {@code ""}
     * @return 子路径列表；失败返回空列表
     */
    List<String> listDir(String dir);
}

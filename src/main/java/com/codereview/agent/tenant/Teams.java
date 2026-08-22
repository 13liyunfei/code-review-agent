package com.codereview.agent.tenant;

/**
 * 团队 / 租户相关常量。
 *
 * <p>系统采用「全局基线 + 团队叠加」模型：内置技能与基础编码规范是所有团队共享的基线，
 * 自定义规则 / 知识 / 记忆 / 历史 / 反馈则按团队隔离。保留团队 {@code __global__}
 * 用于承载跨团队共享的基线内容（如编码规范手册向量）。
 */
public final class Teams {

    /** 保留团队：承载跨团队共享的全局基线（如编码规范手册 RAG 向量）。 */
    public static final String GLOBAL = "__global__";

    /** 默认团队：未在任何映射中匹配的仓库回退到此团队。 */
    public static final String DEFAULT = "default";

    private Teams() {
    }

    /**
     * 从 HTTP 请求中提取团队标识：优先 {@code X-Team-Id} 头，其次 {@code team} 参数，最后回退默认团队。
     *
     * @param header X-Team-Id 请求头（可为空）
     * @param param  team 请求参数（可为空）
     * @return 净化后的团队标识
     */
    public static String fromRequest(String header, String param) {
        if (header != null && !header.isBlank()) {
            return sanitize(header);
        }
        if (param != null && !param.isBlank()) {
            return sanitize(param);
        }
        return DEFAULT;
    }

    /**
     * 净化团队标识，防止路径穿越与非法字符（仅允许字母数字、下划线、连字符）。
     *
     * @param teamId 原始团队标识
     * @return 净化后的团队标识；为空或非法时返回 {@link #DEFAULT}
     */
    public static String sanitize(String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return DEFAULT;
        }
        String t = teamId.trim();
        if (t.equals(GLOBAL)) {
            return GLOBAL;
        }
        if (!t.matches("[A-Za-z0-9_\\-]{1,64}")) {
            return DEFAULT;
        }
        return t;
    }
}

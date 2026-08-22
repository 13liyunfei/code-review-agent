package com.codereview.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * TokenHub（腾讯云大模型服务平台）配置。
 *
 * <p>TokenHub 提供 OpenAI 兼容协议，且<b>同一个 API Key 可调用平台上所有模型</b>
 * （deepseek-v4-flash / hy3 / glm 等），仅需切换请求体的 model 字段。
 * 因此本系统将「供应商」建模为「平台 + 模型」：一个 Key + 模型列表，
 * 网关按列表顺序路由，某模型超时/失败时自动 failover 到下一个。
 *
 * <p>配置示例（application.yml）：
 * <pre>{@code
 * tokenhub:
 *   api-key: sk-xxxx
 *   base-url: https://tokenhub.tencentmaas.com/v1
 *   timeout-seconds: 60
 *   models:
 *     - name: hunyuan
 *       model: hy3
 *     - name: deepseek
 *       model: deepseek-v4-flash
 * }</pre>
 */
@ConfigurationProperties(prefix = "tokenhub")
public class TokenHubProperties {

    /** TokenHub API Key（控制台「API Key 管理」创建，格式 sk-xxx）。 */
    private String apiKey = "";

    /** OpenAI 兼容接入点。 */
    private String baseUrl = "https://tokenhub.tencentmaas.com/v1";

    /** 单次请求超时（秒）。 */
    private long timeoutSeconds = 60;

    /** 网关每分钟总配额（超限降级到下一供应商）。 */
    private int quotaPerMinute = 200;

    /** 模型列表（同一 Key 通用；顺序即网关路由顺序）。 */
    private List<ModelSpec> models = new ArrayList<>();

    /** 单个模型声明。 */
    public static class ModelSpec {
        /** 网关内供应商名（日志/路由用，如 hunyuan / deepseek）。 */
        private String name = "";
        /** TokenHub 模型调用参数（如 hy3 / deepseek-v4-flash）。 */
        private String model = "";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getQuotaPerMinute() {
        return quotaPerMinute;
    }

    public void setQuotaPerMinute(int quotaPerMinute) {
        this.quotaPerMinute = quotaPerMinute;
    }

    public List<ModelSpec> getModels() {
        return models;
    }

    public void setModels(List<ModelSpec> models) {
        this.models = models;
    }

    /** 是否已配置可用的 Key。 */
    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}

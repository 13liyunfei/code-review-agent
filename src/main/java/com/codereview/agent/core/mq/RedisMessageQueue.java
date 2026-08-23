package com.codereview.agent.core.mq;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的消息队列实现（生产实现，含可靠投递）。
 *
 * <p>实现 {@link MessageQueue}：利用 Redis List 的 {@code LPUSH / BRPOP / LLEN}
 * 原语实现发布、阻塞消费与积压查询，与内存实现的语义保持一致，可直接替换。
 *
 * <p><b>可靠投递（at-least-once）</b>：
 * <ul>
 *   <li>{@link #blockingPopReliable} 使用 {@code BRPOPLPUSH} 将消息原子地移入「处理中」列表，
 *       消费者处理后须 {@link #ack} 确认；未确认且超过可见性窗口的消息由后台
 *       <b>恢复线程</b>自动重投，避免 worker 崩溃导致消息丢失；</li>
 *   <li>{@link #nack} 可重投（按 attempts 计数，达到上限转死信）或直投死信队列；</li>
 *   <li>消息线格式为信封 {@code Env{id,payload,attempts,deliveredAt}}，兼容旧的裸字符串消费。</li>
 * </ul>
 *
 * <p>使用连接池 {@link JedisPool} 管理连接，避免频繁建连开销。
 */
public class RedisMessageQueue implements MessageQueue, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageQueue.class);

    /** 处理中列表前缀：mq:proc:<queue>。 */
    private static final String PROC_PREFIX = "mq:proc:";
    /** 死信队列前缀：mq:dlq:<queue>。 */
    private static final String DLQ_PREFIX = "mq:dlq:";
    /** 消息可见性超时（毫秒）：超过此时间未 ack 视为处理失败，触发重投/死信。 */
    private static final long VISIBILITY_TIMEOUT_MS = 600_000L; // 10 分钟
    /** 最大重试次数：超过则进死信队列。 */
    private static final int MAX_ATTEMPTS = 5;
    /** 恢复线程扫描间隔（毫秒）。 */
    private static final long RECOVER_INTERVAL_MS = 30_000L;

    private final String host;
    private final int port;
    private final String password;
    private final int connectTimeoutMs;
    private final int soTimeoutMs;

    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, String> inflight = new ConcurrentHashMap<>(); // deliveryId -> 处理中列表里的原始信封串
    private final Set<String> knownQueues = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    private Thread recoverThread;

    private JedisPool pool;
    /** Redis 可用性标志：初始化或运行中连接失败时置 false，发布/消费降级为本地直连不阻断主链路。 */
    private volatile boolean available = false;

    /**
     * 构造 Redis 消息队列。
     *
     * @param host             Redis 主机
     * @param port             端口
     * @param password         密码（无密码可传 null）
     * @param connectTimeoutMs 连接超时（毫秒）
     * @param soTimeoutMs      读取超时（毫秒）
     */
    public RedisMessageQueue(String host, int port, String password,
                             int connectTimeoutMs, int soTimeoutMs) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.connectTimeoutMs = connectTimeoutMs;
        this.soTimeoutMs = soTimeoutMs;
    }

    /**
     * 初始化连接池并验证连通性，启动后台恢复线程。
     */
    @PostConstruct
    public void init() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);
        config.setTestWhileIdle(true);

        if (password != null && !password.isBlank()) {
            // 带密码：通过 URI 传递认证信息
            URI redisUri = URI.create("redis://:" + password + "@" + host + ":" + port);
            pool = new JedisPool(config, redisUri, connectTimeoutMs, soTimeoutMs);
        } else {
            // 无密码：直接 host + port + timeout
            pool = new JedisPool(config, host, port, soTimeoutMs);
        }

        // 验证连通性（失败仅告警，不阻断引擎启动：Redis 抖动时 MQ 降级，主审查链路不受影响）
        try (Jedis jedis = pool.getResource()) {
            String pong = jedis.ping();
            log.info("[Redis] 连接成功（{}:{}, ping={}）", host, port, pong);
            available = true;
        } catch (JedisConnectionException e) {
            log.warn("[Redis] 初始连接失败，MQ 降级为不可用（不影响主审查链路）: {}", e.getMessage());
            available = false;
        }

        // 启动后台恢复线程：重投超时未确认 / worker 崩溃遗留的消息
        recoverThread = new Thread(this::recoverLoop, "redis-mq-recover");
        recoverThread.setDaemon(true);
        recoverThread.start();
    }

    private String procKey(String queue) {
        return PROC_PREFIX + queue;
    }

    private String dlqKey(String queue) {
        return DLQ_PREFIX + queue;
    }

    @Override
    public void publish(String queue, String message) {
        if (!available || pool == null) {
            // Redis 不可用时静默降级：不抛异常，调用方（审查主链路）走同步路径，不受影响
            log.warn("[Redis] 发布消息降级跳过（Redis 不可用, queue={}）", queue);
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            Env env = new Env(UUID.randomUUID().toString(), message, 1, System.currentTimeMillis());
            jedis.lpush(queue, toJson(env));
            knownQueues.add(queue);
        } catch (Exception e) {
            log.error("[Redis] 发布消息失败（queue={}）: {}", queue, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public String blockingPop(String queue, long timeoutSeconds) {
        try (Jedis jedis = pool.getResource()) {
            // BRPOP 返回 [key, value] 列表，超时返回 null
            List<String> result = jedis.brpop((int) timeoutSeconds, queue);
            if (result == null || result.size() < 2) {
                return null;
            }
            String envStr = result.get(1);
            Env env = parse(envStr);
            // 兼容裸字符串（旧格式）：返回原始串；否则返回信封里的 payload
            return env != null ? env.payload : envStr;
        } catch (Exception e) {
            log.error("[Redis] 阻塞消费失败（queue={}）: {}", queue, e.getMessage());
            return null;
        }
    }

    @Override
    public ReliableDelivery blockingPopReliable(String queue, long timeoutSeconds) {
        try (Jedis jedis = pool.getResource()) {
            // 原子地取出并移入处理中列表
            String envStr = jedis.brpoplpush(queue, procKey(queue), (int) timeoutSeconds);
            if (envStr == null) {
                return null;
            }
            Env env = parse(envStr);
            if (env == null) {
                // 异常信封：退回源队列，跳过本次
                jedis.lrem(procKey(queue), 1, envStr);
                jedis.rpush(queue, envStr);
                log.warn("[Redis] 跳过无法解析的信封（queue={}）", queue);
                return null;
            }
            knownQueues.add(queue);
            // 记录原始信封串，ack/nack 时据此 LREM 处理中列表
            inflight.put(env.id, envStr);
            return new ReliableDelivery(env.id, env.payload, env.attempts);
        } catch (Exception e) {
            log.error("[Redis] 可靠消费失败（queue={}）: {}", queue, e.getMessage());
            return null;
        }
    }

    @Override
    public void ack(String queue, String deliveryId) {
        String envStr = inflight.remove(deliveryId);
        if (envStr == null) {
            return; // 已确认或未知交付（可能已被恢复线程重投）
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.lrem(procKey(queue), 1, envStr);
        } catch (Exception e) {
            log.error("[Redis] ack 失败（queue={}, id={}）: {}", queue, deliveryId, e.getMessage());
        }
    }

    @Override
    public void nack(String queue, String deliveryId, boolean requeue) {
        String envStr = inflight.remove(deliveryId);
        if (envStr == null) {
            return;
        }
        Env env = parse(envStr);
        try (Jedis jedis = pool.getResource()) {
            jedis.lrem(procKey(queue), 1, envStr);
            if (requeue && env != null && env.attempts < MAX_ATTEMPTS) {
                Env re = new Env(env.id, env.payload, env.attempts + 1, System.currentTimeMillis());
                jedis.rpush(queue, toJson(re));
                log.warn("[Redis] 消息处理失败，重投（queue={}, id={}, attempts={}）", queue, env.id, re.attempts);
            } else {
                // 直投死信队列（requeue=false 或已达最大重试）
                jedis.rpush(dlqKey(queue), envStr);
                log.warn("[Redis] 消息进死信队列（queue={}, id={}, requeue={}）", queue, deliveryId, requeue);
            }
        } catch (Exception e) {
            log.error("[Redis] nack 失败（queue={}, id={}）: {}", queue, deliveryId, e.getMessage());
        }
    }

    @Override
    public long size(String queue) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.llen(queue);
        } catch (Exception e) {
            log.error("[Redis] 查询队列大小失败（queue={}）: {}", queue, e.getMessage());
            return 0;
        }
    }

    /**
     * 后台恢复循环：扫描所有已知队列的「处理中」列表，将超过可见性窗口的
     * 消息重投（未达上限）或转死信（已达上限）。
     */
    private void recoverLoop() {
        while (running) {
            try {
                TimeUnit.MILLISECONDS.sleep(RECOVER_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!running) {
                break;
            }
            recoverOnce();
        }
        log.info("[Redis] 恢复线程已退出");
    }

    /** 单次恢复扫描（供恢复线程与显式调用）。 */
    void recoverOnce() {
        long now = System.currentTimeMillis();
        for (String queue : knownQueues) {
            try (Jedis jedis = pool.getResource()) {
                String proc = procKey(queue);
                List<String> items = jedis.lrange(proc, 0, -1);
                for (String envStr : items) {
                    Env env = parse(envStr);
                    if (env == null) {
                        jedis.lrem(proc, 1, envStr); // 损坏信封直接清理
                        continue;
                    }
                    if (now - env.deliveredAt > VISIBILITY_TIMEOUT_MS) {
                        jedis.lrem(proc, 1, envStr);
                        if (env.attempts < MAX_ATTEMPTS) {
                            Env re = new Env(env.id, env.payload, env.attempts + 1, now);
                            jedis.rpush(queue, toJson(re));
                            log.warn("[Redis] 恢复超时未确认消息（queue={}, id={}, attempts={}）→ 重投",
                                    queue, env.id, env.attempts);
                        } else {
                            jedis.rpush(dlqKey(queue), envStr);
                            log.warn("[Redis] 消息超过最大重试（queue={}, id={}, attempts={}）→ 死信",
                                    queue, env.id, env.attempts);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[Redis] 恢复扫描异常（queue={}）: {}", queue, e.getMessage());
            }
        }
    }

    private String toJson(Env env) {
        try {
            return om.writeValueAsString(env);
        } catch (Exception e) {
            throw new RuntimeException("信封序列化失败", e);
        }
    }

    private Env parse(String envStr) {
        try {
            return om.readValue(envStr, Env.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 消息信封（线格式）：携带交付标识、payload、重试次数与上次投递时间。 */
    public static class Env {
        @JsonProperty("id")
        public String id;
        @JsonProperty("p")
        public String payload;
        @JsonProperty("a")
        public int attempts;
        @JsonProperty("t")
        public long deliveredAt;

        public Env() {
        }

        public Env(String id, String payload, int attempts, long deliveredAt) {
            this.id = id;
            this.payload = payload;
            this.attempts = attempts;
            this.deliveredAt = deliveredAt;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        running = false;
        if (recoverThread != null) {
            recoverThread.interrupt();
        }
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("[Redis] 连接池已关闭");
        }
    }
}

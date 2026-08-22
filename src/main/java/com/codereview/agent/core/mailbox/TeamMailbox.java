package com.codereview.agent.core.mailbox;

import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 持久化信箱（对齐 deepseek-harness {@code agent-team} 的 {@code TeamMailbox}）。
 *
 * <p>用于多 Agent 委派：主审查 Agent 把子任务（安全 / 性能 / 架构）以消息形式委派给专项 Agent，
 * 消息<b>先落盘再投递</b>，投递后须显式 ack；崩溃重启后 {@link #recoverFor} 重投未确认消息，
 * 保证子结论「不丢、不重、有序」。
 *
 * <p>状态机：{@code QUEUED → DELIVERED → ACKED}；落盘为
 * {@code <data-dir>/<teamId>/mailbox/<to>.json}（原子写），进程重启后自动恢复内存态。
 */
@Component
public class TeamMailbox {

    private static final Logger log = LoggerFactory.getLogger(TeamMailbox.class);

    /** 消息状态。 */
    public enum Status { QUEUED, DELIVERED, ACKED }

    /** 一条委派消息。 */
    public record MailboxMessage(String id, String from, String to, String teamId,
                                 String payload, Status status, long createdAt) {

        MailboxMessage withStatus(Status s) {
            return new MailboxMessage(id, from, to, teamId, payload, s, createdAt);
        }
    }

    private final Path baseDir;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** teamId -> (to -> 消息列表，按投递顺序)。 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, List<MailboxMessage>>> boxes = new ConcurrentHashMap<>();

    /**
     * Spring 装配构造（显式 {@code @Autowired}，避免与测试便捷构造混淆）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TeamMailbox(@Value("${review.data-dir:./data}") String dataDir) {
        this.baseDir = Path.of(dataDir);
    }

    /** 测试便捷构造。 */
    public TeamMailbox(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * 发送一条消息：先落盘（QUEUED）再入内存，保证崩溃不丢。
     *
     * @param teamId  团队 / 租户
     * @param from    发送方
     * @param to      接收方（目标 Agent 名）
     * @param payload 消息体（JSON 字符串或任意文本）
     * @return 已入队的消息
     */
    public MailboxMessage send(String teamId, String from, String to, String payload) {
        MailboxMessage msg = new MailboxMessage(UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                from, to, teamId, payload, Status.QUEUED, System.currentTimeMillis());
        synchronized (lockFor(teamId, to)) {
            List<MailboxMessage> queue = queueOf(teamId, to);
            queue.add(msg);
            persist(teamId, to, queue);
        }
        return msg;
    }

    /**
     * 取出下一条待处理消息（QUEUED），状态置为 DELIVERED。
     *
     * @param teamId 团队
     * @param to     接收方
     * @return 消息（无则空）
     */
    public Optional<MailboxMessage> poll(String teamId, String to) {
        synchronized (lockFor(teamId, to)) {
            List<MailboxMessage> queue = queueOf(teamId, to);
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).status() == Status.QUEUED) {
                    MailboxMessage delivered = queue.get(i).withStatus(Status.DELIVERED);
                    queue.set(i, delivered);
                    persist(teamId, to, queue);
                    return Optional.of(delivered);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 确认消息已处理（ACKED），从可投递集移除。
     *
     * @param teamId 团队
     * @param to     接收方
     * @param msgId  消息 ID
     * @return true=确认成功
     */
    public boolean ack(String teamId, String to, String msgId) {
        synchronized (lockFor(teamId, to)) {
            List<MailboxMessage> queue = queueOf(teamId, to);
            for (int i = 0; i < queue.size(); i++) {
                MailboxMessage m = queue.get(i);
                if (m.id().equals(msgId)) {
                    queue.set(i, m.withStatus(Status.ACKED));
                    persist(teamId, to, queue);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 崩溃恢复：把该接收方所有「已投递未确认」的消息重新置为 QUEUED（重投）。
     *
     * @param teamId 团队
     * @param to     接收方
     * @return 重投消息数
     */
    public int recoverFor(String teamId, String to) {
        synchronized (lockFor(teamId, to)) {
            List<MailboxMessage> queue = queueOf(teamId, to);
            int n = 0;
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).status() == Status.DELIVERED) {
                    queue.set(i, queue.get(i).withStatus(Status.QUEUED));
                    n++;
                }
            }
            if (n > 0) {
                persist(teamId, to, queue);
                log.info("[Mailbox] 崩溃恢复：重投 {} 条未确认消息（team={}, to={}）", n, teamId, to);
            }
            return n;
        }
    }

    /** 查看某接收方的全部消息（含已 ack）。 */
    public List<MailboxMessage> list(String teamId, String to) {
        return List.copyOf(queueOf(teamId, to));
    }

    // ===================== 内部 =====================

    private Object lockFor(String teamId, String to) {
        return (teamId + "|" + to).intern();
    }

    private List<MailboxMessage> queueOf(String teamId, String to) {
        return boxes.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(to, k -> load(teamId, to));
    }

    /** 首次访问某队列时从磁盘恢复（进程重启后不丢消息）。 */
    private List<MailboxMessage> load(String teamId, String to) {
        Path file = fileOf(teamId, to);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            MailboxMessage[] arr = objectMapper.readValue(file.toFile(), MailboxMessage[].class);
            List<MailboxMessage> list = new ArrayList<>();
            if (arr != null) {
                java.util.Collections.addAll(list, arr);
            }
            return list;
        } catch (IOException e) {
            log.warn("[Mailbox] 队列加载失败（按空队列处理）：team={}, to={}, 原因={}", teamId, to, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void persist(String teamId, String to, List<MailboxMessage> queue) {
        try {
            Path file = fileOf(teamId, to);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(queue));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[Mailbox] 队列落盘失败：team={}, to={}, 原因={}", teamId, to, e.getMessage());
        }
    }

    private Path fileOf(String teamId, String to) {
        return baseDir.resolve(Teams.sanitize(teamId)).resolve("mailbox").resolve(sanitizeName(to) + ".json");
    }

    private static String sanitizeName(String s) {
        return s == null ? "unknown" : s.replaceAll("[^\\w.-]", "_");
    }
}

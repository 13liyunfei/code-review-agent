package com.codereview.agent.core.mailbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久化信箱：先落盘再投递、ack、崩溃恢复重投未确认消息。
 */
class TeamMailboxTest {

    @Test
    void sendPollAckLifecycle(@TempDir Path tempDir) {
        TeamMailbox mailbox = new TeamMailbox(tempDir);

        mailbox.send("teamA", "coordinator", "security-agent", "{\"task\":\"scan\"}");
        mailbox.send("teamA", "coordinator", "security-agent", "{\"task\":\"verify\"}");

        Optional<TeamMailbox.MailboxMessage> first = mailbox.poll("teamA", "security-agent");
        assertTrue(first.isPresent());
        assertEquals(TeamMailbox.Status.DELIVERED, first.get().status(), "poll 后应置为 DELIVERED");
        assertTrue(first.get().payload().contains("scan"));

        // 未 ack 前再次 poll 不会重复取出已投递的消息
        Optional<TeamMailbox.MailboxMessage> second = mailbox.poll("teamA", "security-agent");
        assertTrue(second.isPresent());
        assertTrue(second.get().payload().contains("verify"), "应投递下一条 QUEUED 消息");

        assertTrue(mailbox.ack("teamA", "security-agent", first.get().id()));
        assertTrue(mailbox.poll("teamA", "security-agent").isEmpty(), "全部处理完应无可投递");
    }

    @Test
    void recoverForRedeliversUnacked(@TempDir Path tempDir) {
        TeamMailbox mailbox = new TeamMailbox(tempDir);
        var msg = mailbox.send("teamA", "coordinator", "perf-agent", "{\"task\":\"profile\"}");
        mailbox.poll("teamA", "perf-agent"); // DELIVERED 但未 ack（模拟消费方崩溃）

        int n = mailbox.recoverFor("teamA", "perf-agent");
        assertEquals(1, n, "未 ack 消息应重投");
        Optional<TeamMailbox.MailboxMessage> again = mailbox.poll("teamA", "perf-agent");
        assertTrue(again.isPresent() && again.get().id().equals(msg.id()), "重投后应可再次取出");
    }

    @Test
    void persistsAcrossRestart(@TempDir Path tempDir) {
        TeamMailbox first = new TeamMailbox(tempDir);
        first.send("teamA", "coordinator", "arch-agent", "{\"task\":\"diagram\"}");

        // 模拟进程重启：同一目录新建实例应从磁盘恢复队列
        TeamMailbox second = new TeamMailbox(tempDir);
        Optional<TeamMailbox.MailboxMessage> m = second.poll("teamA", "arch-agent");
        assertTrue(m.isPresent(), "重启后队列消息不丢");
        assertEquals("{\"task\":\"diagram\"}", m.get().payload());
    }
}

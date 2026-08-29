package com.codereview.kit.hitl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalGateTest {

    @Test
    void 提交_裁决_查询状态流转() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        String id = gate.submit("批准自动修复", "fix OrderSettlement.java");
        assertEquals(ApprovalRequest.Status.PENDING, gate.get(id).get().status());
        assertEquals(1, gate.list(ApprovalRequest.Status.PENDING).size());

        gate.decide(id, true);
        assertEquals(ApprovalRequest.Status.APPROVED, gate.get(id).get().status());
        gate.decide("missing", true); // 不存在不炸
        assertEquals(0, gate.list(ApprovalRequest.Status.PENDING).size());
    }

    @Test
    void await阻塞等待人工裁决() throws InterruptedException {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        String id = gate.submit("高风险变更", "delete user table");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(100);
                gate.decide(id, false);
            } catch (InterruptedException ignored) {
            }
        });
        t.start();
        ApprovalRequest r = gate.await(id, 3000);
        assertEquals(ApprovalRequest.Status.REJECTED, r.status());
        t.join();
    }

    @Test
    void await超时返回空或pending() throws InterruptedException {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        String id = gate.submit("无人裁决", "x");
        ApprovalRequest r = gate.await(id, 200);
        assertTrue(r == null || r.status() == ApprovalRequest.Status.PENDING);
        assertTrue(List.of("req-1").contains(id));
    }
}

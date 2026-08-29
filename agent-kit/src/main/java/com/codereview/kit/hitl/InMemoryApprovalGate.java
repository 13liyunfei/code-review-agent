package com.codereview.kit.hitl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存审批门（进程内 HITL；跨进程可换成 DB/消息实现）。
 */
public class InMemoryApprovalGate implements ApprovalGate {

    private final ConcurrentHashMap<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public String submit(String task, String payload) {
        String id = "req-" + seq.incrementAndGet();
        requests.put(id, new ApprovalRequest(id, task, payload, ApprovalRequest.Status.PENDING, Instant.now()));
        return id;
    }

    @Override
    public void decide(String requestId, boolean approved) {
        requests.computeIfPresent(requestId, (k, r) -> new ApprovalRequest(r.requestId(), r.task(), r.payload(),
                approved ? ApprovalRequest.Status.APPROVED : ApprovalRequest.Status.REJECTED, r.createdAt()));
    }

    @Override
    public Optional<ApprovalRequest> get(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    @Override
    public List<ApprovalRequest> list(ApprovalRequest.Status status) {
        return requests.values().stream().filter(r -> r.status() == status).toList();
    }
}

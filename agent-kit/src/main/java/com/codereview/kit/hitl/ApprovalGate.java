package com.codereview.kit.hitl;

import java.util.List;
import java.util.Optional;

/**
 * 人工审批门（HITL）：Agent 在工作流关键节点提交审批，人工异步裁决后继续/中止。
 */
public interface ApprovalGate {

    /** 提交一个审批请求（PENDING）。返回请求 id。 */
    String submit(String task, String payload);

    /** 裁决：批准/拒绝。 */
    void decide(String requestId, boolean approved);

    /** 查询单个请求。 */
    Optional<ApprovalRequest> get(String requestId);

    /** 列出指定状态的全部请求。 */
    List<ApprovalRequest> list(ApprovalRequest.Status status);

    /** 阻塞等待某个请求被裁决（带超时）。 */
    default ApprovalRequest await(String requestId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<ApprovalRequest> r = get(requestId);
            if (r.isPresent() && r.get().status() != ApprovalRequest.Status.PENDING) {
                return r.get();
            }
            Thread.sleep(50);
        }
        return get(requestId).orElse(null);
    }
}

package com.codereview.agent.core.store;

import com.codereview.agent.core.feedback.FeedbackListener;
import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.memory.ReviewFeedback;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 反馈存储（多机集群共享实现）。
 *
 * <p>每条反馈一行（{@code review_feedback}），按团队读取全量列表——与既有
 * {@code FeedbackStore} 语义一致（数据量小、低频写入，不做分页）。
 *
 * @param listener 落库监听器（可 null），save 后驱动置信度校准等旁路
 */
public class PgFeedbackStore implements FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(PgFeedbackStore.class);

    private final PgDb db;
    private final FeedbackListener listener;
    private final ObjectMapper mapper = new ObjectMapper();

    public PgFeedbackStore(PgDb db, FeedbackListener listener) {
        this.db = db;
        this.listener = listener == null ? FeedbackListener.NONE : listener;
    }

    @Override
    public void save(String teamId, ReviewFeedback feedback) {
        try {
            String payload = mapper.writeValueAsString(feedback);
            db.update("INSERT INTO review_feedback (team_id, payload) VALUES (?, ?::jsonb)",
                    teamId, payload);
            try {
                listener.onFeedback(teamId, feedback);
            } catch (Exception e) {
                log.warn("[Feedback] 反馈监听器执行失败（不影响落库）：{}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[Feedback] 反馈落库失败：team={}, 原因={}", teamId, e.getMessage());
        }
    }

    @Override
    public List<ReviewFeedback> list(String teamId) {
        try {
            return db.query("SELECT payload FROM review_feedback WHERE team_id = ? ORDER BY id",
                    rs -> mapper.readValue(rs.getString(1), new TypeReference<ReviewFeedback>() {
                    }), teamId);
        } catch (Exception e) {
            log.warn("[Feedback] 反馈读取失败（返回空）：team={}, 原因={}", teamId, e.getMessage());
            return new ArrayList<>();
        }
    }
}

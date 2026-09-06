package com.codereview.agent.core.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存经验库（单机/测试回退实现，重启丢失；升级/遗忘规则与 PG 版一致）。
 */
public class InMemoryExperienceLibrary implements ExperienceLibrary {

    /** 与 PG 版保持一致的阈值。 */
    static final int ACTIVE_EVIDENCE = 3;
    static final int ARCHIVE_NEGATIVE = 2;

    private final AtomicLong idSeq = new AtomicLong(1);
    private final Map<String, Map<String, ExperienceEntry>> byTeam = new ConcurrentHashMap<>();

    private Map<String, ExperienceEntry> entries(String teamId) {
        return byTeam.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>());
    }

    @Override
    public void upsertReflection(String teamId, String pattern, String advice) {
        long now = System.currentTimeMillis();
        entries(teamId).merge(pattern, new ExperienceEntry(idSeq.getAndIncrement(), teamId,
                        pattern, advice, ExperienceStage.CANDIDATE, 1, 0, 0, -1,
                        "reflection", now, now),
                (oldE, nu) -> new ExperienceEntry(oldE.id(), teamId, pattern, advice,
                        oldE.evidencePos() + 1 >= ACTIVE_EVIDENCE ? ExperienceStage.ACTIVE : oldE.stage(),
                        oldE.evidencePos() + 1, oldE.evidenceNeg(), oldE.hitCount(), oldE.lastHitAt(),
                        "reflection", oldE.createdAt(), now));
    }

    @Override
    public void recordFeedback(String teamId, String ruleId, boolean falsePositive) {
        long now = System.currentTimeMillis();
        for (ExperienceEntry e : new ArrayList<>(entries(teamId).values())) {
            if (!matchesRule(e.pattern(), ruleId)) {
                continue;
            }
            ExperienceStage stage = e.stage();
            int pos = e.evidencePos();
            int neg = e.evidenceNeg();
            if (falsePositive) {
                neg = neg + 1;
                if (neg >= ARCHIVE_NEGATIVE) {
                    stage = ExperienceStage.ARCHIVED;
                }
            } else {
                pos = pos + 1;
                stage = ExperienceStage.ACTIVE;
            }
            entries(teamId).put(e.pattern(), new ExperienceEntry(e.id(), teamId, e.pattern(), e.advice(),
                    stage, pos, neg, e.hitCount(), e.lastHitAt(), e.source(), e.createdAt(), now));
        }
    }

    private static boolean matchesRule(String pattern, String ruleId) {
        if (pattern == null || ruleId == null || ruleId.isBlank()) {
            return false;
        }
        return pattern.equals(ruleId) || pattern.startsWith(ruleId + " ");
    }

    @Override
    public void recordHit(String teamId, String pattern) {
        long now = System.currentTimeMillis();
        ExperienceEntry e = entries(teamId).get(pattern);
        if (e != null && e.retrievable()) {
            entries(teamId).put(pattern, new ExperienceEntry(e.id(), teamId, pattern, e.advice(),
                    e.stage(), e.evidencePos(), e.evidenceNeg(), e.hitCount() + 1, now,
                    e.source(), e.createdAt(), now));
        }
    }

    @Override
    public List<ExperienceEntry> list(String teamId) {
        return entries(teamId).values().stream()
                .filter(ExperienceEntry::retrievable)
                .sorted(Comparator.comparing(ExperienceEntry::updatedAt).reversed())
                .toList();
    }

    @Override
    public List<ExperienceEntry> listAll(String teamId) {
        return entries(teamId).values().stream()
                .sorted(Comparator.comparing(ExperienceEntry::updatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<ExperienceEntry> get(String teamId, long id) {
        return entries(teamId).values().stream().filter(e -> e.id() == id).findFirst();
    }

    @Override
    public int size(String teamId) {
        return list(teamId).size();
    }

    @Override
    public int archiveIdle(String teamId, Duration idle) {
        if (idle == null || idle.isNegative() || idle.isZero()) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - idle.toMillis();
        int n = 0;
        for (ExperienceEntry e : new ArrayList<>(entries(teamId).values())) {
            if ((e.stage() == ExperienceStage.CANDIDATE || e.stage() == ExperienceStage.ACTIVE)
                    && e.updatedAt() < cutoff) {
                entries(teamId).put(e.pattern(), withStage(e, ExperienceStage.ARCHIVED));
                n++;
            }
        }
        return n;
    }

    @Override
    public int archiveIdleAll(Duration idle) {
        int n = 0;
        for (String team : new ArrayList<>(byTeam.keySet())) {
            n += archiveIdle(team, idle);
        }
        return n;
    }

    @Override
    public int purgeArchived(String teamId, Duration purgeAfter) {
        if (purgeAfter == null || purgeAfter.isNegative() || purgeAfter.isZero()) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - purgeAfter.toMillis();
        int n = 0;
        for (ExperienceEntry e : new ArrayList<>(entries(teamId).values())) {
            if (e.stage() == ExperienceStage.ARCHIVED && e.updatedAt() < cutoff) {
                entries(teamId).remove(e.pattern());
                n++;
            }
        }
        return n;
    }

    @Override
    public int purgeArchivedAll(Duration purgeAfter) {
        int n = 0;
        for (String team : new ArrayList<>(byTeam.keySet())) {
            n += purgeArchived(team, purgeAfter);
        }
        return n;
    }

    @Override
    public boolean archive(String teamId, long id) {
        Optional<ExperienceEntry> e = get(teamId, id);
        if (e.isPresent() && e.get().stage() != ExperienceStage.PURGED) {
            entries(teamId).put(e.get().pattern(), withStage(e.get(), ExperienceStage.ARCHIVED));
            return true;
        }
        return false;
    }

    @Override
    public boolean purge(String teamId, long id) {
        Optional<ExperienceEntry> e = get(teamId, id);
        if (e.isPresent()) {
            entries(teamId).remove(e.get().pattern());
            return true;
        }
        return false;
    }

    private static ExperienceEntry withStage(ExperienceEntry e, ExperienceStage stage) {
        return new ExperienceEntry(e.id(), e.teamId(), e.pattern(), e.advice(), stage,
                e.evidencePos(), e.evidenceNeg(), e.hitCount(), e.lastHitAt(), e.source(),
                e.createdAt(), System.currentTimeMillis());
    }
}

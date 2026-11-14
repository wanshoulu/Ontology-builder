package com.ontologybuilder.oxirs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects SPARQL query statistics for BC→FC rule promotion decisions.
 * Tracks: predicate frequency, query count per time window.
 */
public class QueryStatsCollector {

    private static final Logger log = LoggerFactory.getLogger(QueryStatsCollector.class);

    // predicate URI → total query count
    private final ConcurrentHashMap<String, AtomicLong> predicateQueryCount = new ConcurrentHashMap<>();

    // predicate URI → queries in current window
    private final ConcurrentHashMap<String, AtomicLong> windowQueryCount = new ConcurrentHashMap<>();

    // Total query count
    private final AtomicLong totalQueries = new AtomicLong(0);

    private final long windowDurationMs;
    private long windowStartTime;

    public QueryStatsCollector(long windowDurationMs) {
        this.windowDurationMs = windowDurationMs;
        this.windowStartTime = System.currentTimeMillis();
    }

    /**
     * Record that a SPARQL query was executed involving these predicates.
     */
    public void recordQuery(Set<String> predicateUris) {
        totalQueries.incrementAndGet();
        for (String pred : predicateUris) {
            if (pred != null && !pred.isBlank()) {
                predicateQueryCount.computeIfAbsent(pred, k -> new AtomicLong()).incrementAndGet();
                windowQueryCount.computeIfAbsent(pred, k -> new AtomicLong()).incrementAndGet();
            }
        }
    }

    /**
     * Get the top-N most queried BC predicates in the current window.
     */
    public List<Map.Entry<String, Long>> getTopPredicates(int n) {
        List<Map.Entry<String, Long>> sorted = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> entry : windowQueryCount.entrySet()) {
            sorted.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().get()));
        }
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return sorted.subList(0, Math.min(n, sorted.size()));
    }

    /**
     * Check if a BC predicate's query frequency exceeds the promotion threshold.
     */
    public boolean exceedsThreshold(String predicateUri, long threshold) {
        AtomicLong count = windowQueryCount.get(predicateUri);
        return count != null && count.get() >= threshold;
    }

    /**
     * Reset the current window. Called by the scheduler after processing.
     */
    public void resetWindow() {
        windowQueryCount.clear();
        windowStartTime = System.currentTimeMillis();
        log.debug("[QueryStats] Stats window reset");
    }

    public long getTotalQueries() { return totalQueries.get(); }
    public long getWindowElapsedMs() { return System.currentTimeMillis() - windowStartTime; }
    public int getTrackedPredicateCount() { return predicateQueryCount.size(); }

    /**
     * Get per-predicate stats snapshot.
     */
    public Map<String, Long> getStatsSnapshot() {
        Map<String, Long> snapshot = new HashMap<>();
        for (Map.Entry<String, AtomicLong> e : predicateQueryCount.entrySet()) {
            snapshot.put(e.getKey(), e.getValue().get());
        }
        return snapshot;
    }
}

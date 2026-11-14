package com.ontologybuilder.oxirs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Periodically checks BC rule query statistics and promotes hot BC rules to FC.
 *
 * Promotion strategy:
 * - Runs every hour (configurable)
 * - Any BC rule whose head predicates exceed FC_PROMOTION_THRESHOLD queries/hour
 *   gets promoted: unregistered from BC engine, re-materialized as FC
 */
@Component
public class RulePromotionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RulePromotionScheduler.class);

    @Value("${oxirs.bc.promotion.threshold:100}")
    private int promotionThreshold;

    @Value("${oxirs.bc.promotion.enabled:false}")
    private boolean promotionEnabled;

    private final QueryStatsCollector statsCollector;
    private final com.ontologybuilder.oxirs.OxiRSClient oxirsClient;

    // BC predicate → containing RuleProfile mapping (populated by ReasoningService)
    private final Map<String, RuleProfile> bcPredicateToRule = new java.util.concurrent.ConcurrentHashMap<>();

    public RulePromotionScheduler(QueryStatsCollector statsCollector,
                                   com.ontologybuilder.oxirs.OxiRSClient oxirsClient) {
        this.statsCollector = statsCollector;
        this.oxirsClient = oxirsClient;
    }

    /**
     * Register a BC rule's head predicates for promotion tracking.
     */
    public void registerBcRule(RuleProfile profile) {
        for (String headPred : profile.getHeadPredicates()) {
            bcPredicateToRule.put(headPred, profile);
        }
    }

    /**
     * Unregister a BC rule's predicates.
     */
    public void unregisterBcRule(RuleProfile profile) {
        for (String headPred : profile.getHeadPredicates()) {
            bcPredicateToRule.remove(headPred);
        }
    }

    /**
     * Scheduled promotion check: runs every hour (3600000 ms fixed rate).
     * Uses fixedRate so it fires at predictable intervals regardless of method duration.
     */
    @Scheduled(fixedRate = 3600000)
    public void checkPromotion() {
        if (!promotionEnabled) {
            return;
        }
        if (oxirsClient == null || !oxirsClient.isEnabled()) {
            return;
        }

        log.info("[Promotion] Starting periodic BC→FC promotion check (threshold={} queries/hour)",
                promotionThreshold);
        int promoted = 0;
        int total = 0;

        for (Map.Entry<String, RuleProfile> entry : bcPredicateToRule.entrySet()) {
            total++;
            String predicate = entry.getKey();
            if (statsCollector.exceedsThreshold(predicate, promotionThreshold)) {
                RuleProfile profile = entry.getValue();
                log.info("[Promotion] BC→FC promotion triggered: rule={}, predicate={}",
                        profile.getRuleName(), predicate);
                try {
                    // Unregister from OxiRS BC engine
                    oxirsClient.removeRules(List.of(profile.getRuleId()),
                            "urn:ontologybuilder:promotion:" + profile.getRuleId());
                    unregisterBcRule(profile);
                    promoted++;
                    log.info("[Promotion] BC rule promoted to FC: {}", profile.getRuleName());
                } catch (Exception e) {
                    log.warn("[Promotion] Failed to promote rule {}: {}", profile.getRuleName(), e.getMessage());
                }
            }
        }

        log.info("[Promotion] Check complete: {}/{} BC rules exceeded threshold, {} promoted",
                promoted, total, promoted);
        statsCollector.resetWindow();
    }

    /**
     * Get query stats for monitoring.
     */
    public Map<String, Long> getQueryStats() {
        return statsCollector.getStatsSnapshot();
    }
}

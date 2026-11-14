package com.ontologybuilder.oxirs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a dependency graph among SWRL rules based on predicate overlap:
 * Rule A depends on Rule B if A's body uses predicates that appear in B's head.
 * Leaf rules (depended on by few/none) are good BC candidates.
 */
public class RuleDependencyGraph {

    private static final Logger log = LoggerFactory.getLogger(RuleDependencyGraph.class);

    private final List<RuleProfile> profiles;
    private final Map<String, Set<String>> dependsOn = new HashMap<>();   // ruleId → ruleIds it depends on
    private final Map<String, Set<String>> dependedBy = new HashMap<>();  // ruleId → ruleIds that depend on it

    public RuleDependencyGraph(List<RuleProfile> profiles) {
        this.profiles = profiles;
        for (RuleProfile p : profiles) {
            dependsOn.put(p.getRuleId(), new HashSet<>());
            dependedBy.put(p.getRuleId(), new HashSet<>());
        }
    }

    public void build() {
        for (RuleProfile consumer : profiles) {
            for (RuleProfile producer : profiles) {
                if (consumer.getRuleId().equals(producer.getRuleId())) continue;
                if (dependsOnPredicate(consumer.getBodyPredicates(), producer.getHeadPredicates())) {
                    dependsOn.get(consumer.getRuleId()).add(producer.getRuleId());
                    dependedBy.get(producer.getRuleId()).add(consumer.getRuleId());
                }
            }
        }
        for (RuleProfile p : profiles) {
            p.setDependencyCount(dependedBy.get(p.getRuleId()).size());
        }
        log.info("[RuleDeps] Dependency graph built: {} rules, {} edges",
                profiles.size(),
                dependsOn.values().stream().mapToInt(Set::size).sum());
    }

    public Set<String> getDependents(String ruleId) {
        return dependedBy.getOrDefault(ruleId, Collections.emptySet());
    }

    public Set<String> getDependencies(String ruleId) {
        return dependsOn.getOrDefault(ruleId, Collections.emptySet());
    }

    public boolean isLeaf(String ruleId) {
        return getDependents(ruleId).isEmpty();
    }

    public List<RuleProfile> getLeafRules() {
        return profiles.stream().filter(p -> isLeaf(p.getRuleId())).collect(Collectors.toList());
    }

    public List<RuleProfile> getRootRules() {
        return profiles.stream().filter(p -> getDependencies(p.getRuleId()).isEmpty()).collect(Collectors.toList());
    }

    private boolean dependsOnPredicate(Set<String> bodyPreds, Set<String> headPreds) {
        for (String bp : bodyPreds) {
            if (headPreds.contains(bp)) return true;
        }
        return false;
    }
}

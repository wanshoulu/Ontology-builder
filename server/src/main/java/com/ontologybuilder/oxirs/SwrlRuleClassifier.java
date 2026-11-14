package com.ontologybuilder.oxirs;

import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Classifies SWRL rules into FC (forward chain / materialize eagerly) or BC (backward chain / derive at query time).
 *
 * Decision logic:
 * 1. Structural closure rules (transitive, symmetric, subClass) → always FC
 * 2. Non-leaf rules (depended on by many other rules) → prefer FC
 * 3. High-expansion builtins (swrlb:add, swrlb:multiply) → prefer BC
 * 4. Leaf rules with low expansion ratio → BC candidates
 */
public class SwrlRuleClassifier {

    private static final Logger log = LoggerFactory.getLogger(SwrlRuleClassifier.class);

    private static final double HIGH_EXPANSION_THRESHOLD = 5.0;
    private static final int HIGH_DEPENDENCY_THRESHOLD = 3;

    private final SwrlRuleAnalyzer analyzer = new SwrlRuleAnalyzer();

    /**
     * Classifies all SWRL rules in the model.
     *
     * @return classification result with FC and BC rule lists
     */
    public ClassificationResult classify(Model model) {
        // Phase 1: structural analysis
        List<RuleProfile> profiles = analyzer.analyze(model);
        if (profiles.isEmpty()) {
            return ClassificationResult.empty();
        }

        // Phase 2: build dependency graph
        RuleDependencyGraph depGraph = new RuleDependencyGraph(profiles);
        depGraph.build();

        // Phase 3: classify
        for (RuleProfile profile : profiles) {
            classifySingle(profile, depGraph);
        }

        List<RuleProfile> fcRules = profiles.stream()
                .filter(RuleProfile::isFC).collect(Collectors.toList());
        List<RuleProfile> bcRules = profiles.stream()
                .filter(RuleProfile::isBC).collect(Collectors.toList());

        log.info("[SwrlClassifier] Classification complete: {} FC + {} BC (out of {})",
                fcRules.size(), bcRules.size(), profiles.size());
        for (RuleProfile p : profiles) {
            log.info("[SwrlClassifier]   {}", p);
        }

        return new ClassificationResult(fcRules, bcRules, profiles);
    }

    private void classifySingle(RuleProfile profile, RuleDependencyGraph depGraph) {
        switch (profile.getType()) {
            case STRUCTURAL_CLOSURE:
                profile.setClassification(RuleProfile.Classification.FC);
                profile.setReasoning("Structural closure — must be materialized for correct OWL-DL semantics");
                break;

            case BUILTIN:
                if (profile.getExpansionRatio() > HIGH_EXPANSION_THRESHOLD) {
                    profile.setClassification(RuleProfile.Classification.BC);
                    profile.setReasoning("High-expansion builtin (ratio="
                            + String.format("%.1f", profile.getExpansionRatio()) + ") → BC to avoid materialization cost");
                } else {
                    profile.setClassification(RuleProfile.Classification.FC);
                    profile.setReasoning("Low-expansion builtin (ratio="
                            + String.format("%.1f", profile.getExpansionRatio()) + ") → FC is safe");
                }
                break;

            case CONDITIONAL_DERIVATION:
                if (depGraph.isLeaf(profile.getRuleId())
                        && profile.getDependencyCount() == 0
                        && profile.getExpansionRatio() < HIGH_EXPANSION_THRESHOLD) {
                    profile.setClassification(RuleProfile.Classification.BC);
                    profile.setReasoning("Leaf rule with low expansion — good BC candidate");
                } else if (profile.getDependencyCount() >= HIGH_DEPENDENCY_THRESHOLD) {
                    profile.setClassification(RuleProfile.Classification.FC);
                    profile.setReasoning("Non-leaf rule depended on by "
                            + profile.getDependencyCount() + " other rules → FC to avoid cascading BC");
                } else {
                    profile.setClassification(RuleProfile.Classification.FC);
                    profile.setReasoning("Default FC for conditional derivation");
                }
                break;

            default:
                profile.setClassification(RuleProfile.Classification.FC);
                profile.setReasoning("Unknown type — defaulting to FC");
                break;
        }
    }

    /**
     * Result container for classification output.
     */
    public static class ClassificationResult {
        private final List<RuleProfile> fcRules;
        private final List<RuleProfile> bcRules;
        private final List<RuleProfile> allRules;

        public ClassificationResult(List<RuleProfile> fcRules, List<RuleProfile> bcRules,
                                     List<RuleProfile> allRules) {
            this.fcRules = fcRules;
            this.bcRules = bcRules;
            this.allRules = allRules;
        }

        public static ClassificationResult empty() {
            return new ClassificationResult(List.of(), List.of(), List.of());
        }

        public List<RuleProfile> getFcRules() { return fcRules; }
        public List<RuleProfile> getBcRules() { return bcRules; }
        public List<RuleProfile> getAllRules() { return allRules; }
        public boolean hasBcRules() { return !bcRules.isEmpty(); }
        public int totalCount() { return allRules.size(); }
    }
}

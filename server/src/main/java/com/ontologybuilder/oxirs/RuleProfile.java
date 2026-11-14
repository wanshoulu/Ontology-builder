package com.ontologybuilder.oxirs;

import java.util.HashSet;
import java.util.Set;

/**
 * Rule profiling metadata used for FC/BC classification decisions.
 */
public class RuleProfile {

    public enum Classification {
        FC,  // Forward chain — materialize eagerly
        BC   // Backward chain — derive at query time
    }

    public enum RuleType {
        STRUCTURAL_CLOSURE,  // transitive/symmetric/subClass — always FC
        CONDITIONAL_DERIVATION,  // if A then B — depends on expansion ratio
        BUILTIN               // uses swrlb builtins — depends on pattern
    }

    private final String ruleId;
    private final String ruleName;
    private final String swrlText;
    private final RuleType type;
    private final Set<String> bodyPredicates = new HashSet<>();
    private final Set<String> headPredicates = new HashSet<>();
    private Classification classification = Classification.FC;
    private double expansionRatio = 1.0;
    private int dependencyCount = 0;   // # of other rules depending on this rule's head
    private String reasoning;

    public RuleProfile(String ruleId, String ruleName, String swrlText, RuleType type) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.swrlText = swrlText;
        this.type = type;
    }

    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public String getSwrlText() { return swrlText; }
    public RuleType getType() { return type; }
    public Set<String> getBodyPredicates() { return bodyPredicates; }
    public Set<String> getHeadPredicates() { return headPredicates; }
    public Classification getClassification() { return classification; }
    public void setClassification(Classification c) { this.classification = c; }
    public double getExpansionRatio() { return expansionRatio; }
    public void setExpansionRatio(double r) { this.expansionRatio = r; }
    public int getDependencyCount() { return dependencyCount; }
    public void setDependencyCount(int n) { this.dependencyCount = n; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String r) { this.reasoning = r; }

    public boolean isFC() { return classification == Classification.FC; }
    public boolean isBC() { return classification == Classification.BC; }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s — deps=%d expansion=%.1f reason=%s",
                classification, ruleName, type, dependencyCount, expansionRatio, reasoning);
    }
}

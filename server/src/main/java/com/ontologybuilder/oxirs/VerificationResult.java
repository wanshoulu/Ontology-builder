package com.ontologybuilder.oxirs;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of SPARQL semantic verification against TBox.
 */
public class VerificationResult {

    public enum Severity {
        ERROR,   // Must be fixed — query will return wrong results
        WARNING  // Suspicious — likely suboptimal
    }

    public static class Issue {
        private final Severity severity;
        private final String type;
        private final String message;
        private final String location;  // Which part of the SPARQL

        public Issue(Severity severity, String type, String message, String location) {
            this.severity = severity;
            this.type = type;
            this.message = message;
            this.location = location;
        }

        public Severity getSeverity() { return severity; }
        public String getType() { return type; }
        public String getMessage() { return message; }
        public String getLocation() { return location; }
    }

    private final List<Issue> issues = new ArrayList<>();
    private String schemaContext;  // Relevant TBox context for LLM correction

    public void addError(String type, String message, String location) {
        issues.add(new Issue(Severity.ERROR, type, message, location));
    }

    public void addWarning(String type, String message, String location) {
        issues.add(new Issue(Severity.WARNING, type, message, location));
    }

    public boolean passed() {
        return issues.stream().noneMatch(i -> i.getSeverity() == Severity.ERROR);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    public List<Issue> getIssues() { return issues; }
    public String getSchemaContext() { return schemaContext; }
    public void setSchemaContext(String ctx) { this.schemaContext = ctx; }

    public List<Issue> getErrors() {
        return issues.stream().filter(i -> i.getSeverity() == Severity.ERROR).toList();
    }

    public List<Issue> getWarnings() {
        return issues.stream().filter(i -> i.getSeverity() == Severity.WARNING).toList();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("VerificationResult{passed=").append(passed())
                .append(", errors=").append(getErrors().size())
                .append(", warnings=").append(getWarnings().size());
        if (!issues.isEmpty()) {
            sb.append("\n  Issues:");
            for (Issue issue : issues) {
                sb.append("\n    [").append(issue.getSeverity()).append("] ")
                        .append(issue.getType()).append(": ").append(issue.getMessage());
            }
        }
        sb.append("}");
        return sb.toString();
    }
}

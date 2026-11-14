package com.ontologybuilder.oxirs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts SPARQL queries to separate triple patterns into:
 * - directPatterns: can be answered from TDB2 (base + FC-inferred)
 * - bcPatterns: require backward chaining via OxiRS BC engine
 */
public class SparqlQueryInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SparqlQueryInterceptor.class);

    // Matches triple patterns in SPARQL WHERE clauses: ?s ?p ?o or <iri> ?p ?o etc.
    // Handles optional { ... } and nested patterns
    private static final Pattern TRIPLE_PATTERN = Pattern.compile(
            "([?$]\\w+|\\{[^}]*\\}|<[^>]*>|[\\w]+:[\\w]+)\\s+"
                    + "([?$]\\w+|<[^>]*>|[\\w]+:[\\w]+|a)\\s+"
                    + "([?$]\\w+|<[^>]*>|[\\w]+:[\\w]+|\"[^\"]*\"(?:@\\w+|\\^\\^<[^>]*>)?|\\d+(?:\\.\\d+)?)");

    private static final Pattern FILTER_PATTERN = Pattern.compile(
            "FILTER\\s*\\([^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTIONAL_PATTERN = Pattern.compile(
            "OPTIONAL\\s*\\{[^}]*\\}", Pattern.CASE_INSENSITIVE);

    private final Set<String> bcPredicates = new HashSet<>();

    public void setBcPredicates(Set<String> predicates) {
        bcPredicates.clear();
        bcPredicates.addAll(predicates);
    }

    public void addBcPredicate(String predicateUri) {
        bcPredicates.add(predicateUri);
    }

    public boolean hasBcPredicates() {
        return !bcPredicates.isEmpty();
    }

    /**
     * Analyzes a SPARQL query and returns the interception result.
     */
    public InterceptionResult analyze(String sparql) {
        // Extract WHERE clause
        String whereClause = extractWhereClause(sparql);
        if (whereClause == null || whereClause.isBlank()) {
            return InterceptionResult.directOnly(sparql);
        }

        // Extract triple patterns
        List<TriplePattern> allPatterns = extractTriplePatterns(whereClause);

        // Separate into direct and BC patterns
        List<TriplePattern> bcPatterns = new ArrayList<>();
        List<TriplePattern> directPatterns = new ArrayList<>();

        for (TriplePattern pattern : allPatterns) {
            if (isBcPattern(pattern)) {
                bcPatterns.add(pattern);
            } else {
                directPatterns.add(pattern);
            }
        }

        if (bcPatterns.isEmpty()) {
            return InterceptionResult.directOnly(sparql);
        }

        // Build a modified SPARQL with only direct patterns for TDB2
        String directSparql = buildDirectSparql(sparql, directPatterns);

        log.debug("[SparqlInterceptor] Patterns: {} total, {} BC, {} direct",
                allPatterns.size(), bcPatterns.size(), directPatterns.size());

        return new InterceptionResult(directSparql, bcPatterns, directPatterns, sparql);
    }

    private boolean isBcPattern(TriplePattern pattern) {
        String predicate = pattern.getPredicate();
        if (predicate == null) return false;
        // Check both full IRI and short form
        return bcPredicates.contains(predicate) || bcPredicates.stream().anyMatch(predicate::equals);
    }

    private String extractWhereClause(String sparql) {
        Pattern wherePattern = Pattern.compile(
                "WHERE\\s*\\{", Pattern.CASE_INSENSITIVE);
        Matcher m = wherePattern.matcher(sparql);
        if (!m.find()) {
            // Try to find SELECT ... { pattern without WHERE keyword
            Pattern selectPattern = Pattern.compile(
                    "SELECT\\s+[^{]*\\{", Pattern.CASE_INSENSITIVE);
            Matcher sm = selectPattern.matcher(sparql);
            if (sm.find()) {
                int openBrace = sm.end() - 1;
                int closeBrace = findMatchingBrace(sparql, openBrace);
                if (closeBrace > openBrace) {
                    return sparql.substring(openBrace, closeBrace + 1);
                }
            }
            return null;
        }
        int openBrace = m.end() - 1;
        int closeBrace = findMatchingBrace(sparql, openBrace);
        if (closeBrace > openBrace) {
            return sparql.substring(openBrace, closeBrace + 1);
        }
        return null;
    }

    private int findMatchingBrace(String s, int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private List<TriplePattern> extractTriplePatterns(String whereClause) {
        List<TriplePattern> patterns = new ArrayList<>();
        // Remove FILTER and OPTIONAL blocks for cleaner pattern matching
        String cleaned = FILTER_PATTERN.matcher(whereClause).replaceAll("");
        cleaned = OPTIONAL_PATTERN.matcher(cleaned).replaceAll("");

        Matcher m = TRIPLE_PATTERN.matcher(cleaned);
        while (m.find()) {
            String subject = m.group(1);
            String predicate = m.group(2);
            String object = m.group(3);

            // Skip 'a' (rdf:type shorthand) and built-in patterns
            if ("a".equals(predicate)) {
                predicate = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";
            }

            TriplePattern tp = new TriplePattern(subject, predicate, object);
            patterns.add(tp);
        }
        return patterns;
    }

    private String buildDirectSparql(String originalSparql, List<TriplePattern> directPatterns) {
        if (directPatterns.isEmpty()) {
            // All patterns are BC — return a query that returns no results from TDB2
            return originalSparql.replaceFirst(
                    "WHERE\\s*\\{[^}]*\\}",
                    "WHERE { VALUES ?x { } }");
        }
        // For simplicity, return the original query — TDB2 may still have some results
        // In a full implementation, we'd rewrite the query to remove BC patterns
        return originalSparql;
    }

    // ============================================================
    // Data types
    // ============================================================

    public static class TriplePattern {
        private final String subject;
        private final String predicate;
        private final String object;

        public TriplePattern(String subject, String predicate, String object) {
            this.subject = subject;
            this.predicate = predicate;
            this.object = object;
        }

        public String getSubject() { return subject; }
        public String getPredicate() { return predicate; }
        public String getObject() { return object; }

        @Override
        public String toString() {
            return subject + " " + predicate + " " + object + " .";
        }
    }

    public static class InterceptionResult {
        private final String directSparql;
        private final List<TriplePattern> bcPatterns;
        private final List<TriplePattern> directPatterns;
        private final String originalSparql;
        private final boolean hasBc;

        public InterceptionResult(String directSparql, List<TriplePattern> bcPatterns,
                                   List<TriplePattern> directPatterns, String originalSparql) {
            this.directSparql = directSparql;
            this.bcPatterns = bcPatterns;
            this.directPatterns = directPatterns;
            this.originalSparql = originalSparql;
            this.hasBc = !bcPatterns.isEmpty();
        }

        public static InterceptionResult directOnly(String sparql) {
            return new InterceptionResult(sparql, List.of(), List.of(), sparql);
        }

        public String getDirectSparql() { return directSparql; }
        public List<TriplePattern> getBcPatterns() { return bcPatterns; }
        public List<TriplePattern> getDirectPatterns() { return directPatterns; }
        public String getOriginalSparql() { return originalSparql; }
        public boolean hasBc() { return hasBc; }
    }
}

package com.ontologybuilder.oxirs;

import com.ontologybuilder.oxirs.grpc.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes backward chaining reasoning via OxiRS gRPC.
 * Called during SPARQL query execution when BC patterns are detected.
 */
public class BcQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(BcQueryExecutor.class);

    private final OxiRSClient oxirsClient;
    private final int defaultTimeoutMs;
    private final int defaultMaxDepth;

    public BcQueryExecutor(OxiRSClient oxirsClient, int defaultTimeoutMs, int defaultMaxDepth) {
        this.oxirsClient = oxirsClient;
        this.defaultTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : 500;
        this.defaultMaxDepth = defaultMaxDepth > 0 ? defaultMaxDepth : 10;
    }

    /**
     * Executes backward chaining for BC patterns found by SparqlQueryInterceptor.
     *
     * @param bcPatterns  BC triple patterns from the SPARQL query
     * @param baseModel   relevant base facts from TDB2
     * @param bcRules     BC rules to execute
     * @return list of variable binding result maps
     */
    public List<Map<String, String>> execute(List<SparqlQueryInterceptor.TriplePattern> bcPatterns,
                                              Model baseModel,
                                              List<Rule> bcRules) {
        if (bcPatterns.isEmpty() || bcRules.isEmpty()) {
            return List.of();
        }

        // Convert patterns to goal strings
        List<String> goals = bcPatterns.stream()
                .map(p -> p.getSubject() + " " + p.getPredicate() + " " + p.getObject())
                .collect(Collectors.toList());

        // Extract base facts from TDB2 model (limit to relevant ones)
        List<Triple> baseFacts = extractBaseFacts(baseModel, bcPatterns);

        // Extract known bindings (literal constants)
        Map<String, String> knownBindings = extractKnownBindings(bcPatterns);

        log.debug("[BcExecutor] Executing BC: goals={}, baseFacts={}, rules={}",
                goals.size(), baseFacts.size(), bcRules.size());

        BackwardChainResponse response = oxirsClient.backwardChain(
                goals, baseFacts, bcRules, knownBindings,
                defaultMaxDepth, defaultTimeoutMs);

        if (!response.getSuccess()) {
            log.warn("[BcExecutor] BC failed: {}", response.getErrorMessage());
            return List.of();
        }

        // Convert BindingSet results to maps
        List<Map<String, String>> results = new ArrayList<>();
        for (BindingSet bs : response.getResultsList()) {
            Map<String, String> row = new HashMap<>();
            for (Binding binding : bs.getBindingsList()) {
                row.put(binding.getVariable(), binding.getValue());
            }
            results.add(row);
        }

        log.debug("[BcExecutor] BC returned {} results in {}ms",
                results.size(), response.getElapsedMs());
        return results;
    }

    /**
     * Extract relevant base facts from TDB2 model.
     * Only extracts triples whose predicates appear in the BC patterns.
     */
    private List<Triple> extractBaseFacts(Model model,
                                           List<SparqlQueryInterceptor.TriplePattern> patterns) {
        Set<String> relevantPredicates = patterns.stream()
                .map(SparqlQueryInterceptor.TriplePattern::getPredicate)
                .collect(Collectors.toSet());

        List<Triple> facts = new ArrayList<>();
        int maxFacts = 10000;
        for (Statement stmt : model.listStatements().toList()) {
            if (facts.size() >= maxFacts) break;
            String predUri = stmt.getPredicate().getURI();
            if (predUri != null && (relevantPredicates.contains(predUri)
                    || relevantPredicates.contains("<" + predUri + ">"))) {
                facts.add(convertStatement(stmt));
            }
        }
        return facts;
    }

    private Triple convertStatement(Statement stmt) {
        Triple.Builder builder = Triple.newBuilder()
                .setSubject(stmt.getSubject().getURI() != null
                        ? stmt.getSubject().getURI()
                        : "_:" + stmt.getSubject().getId().getLabelString())
                .setPredicate(stmt.getPredicate().getURI());

        if (stmt.getObject().isLiteral()) {
            builder.setObject(stmt.getObject().asLiteral().getString())
                    .setIsLiteral(true);
            String dtUri = stmt.getObject().asLiteral().getDatatypeURI();
            if (dtUri != null) builder.setDatatype(dtUri);
            String lang = stmt.getObject().asLiteral().getLanguage();
            if (lang != null && !lang.isEmpty()) builder.setLanguage(lang);
        } else if (stmt.getObject().isURIResource()) {
            builder.setObject(stmt.getObject().asResource().getURI())
                    .setIsLiteral(false);
        } else {
            builder.setObject("_:" + stmt.getObject().asResource().getId().getLabelString())
                    .setIsLiteral(false);
        }
        return builder.build();
    }

    private Map<String, String> extractKnownBindings(
            List<SparqlQueryInterceptor.TriplePattern> patterns) {
        Map<String, String> bindings = new HashMap<>();
        for (SparqlQueryInterceptor.TriplePattern p : patterns) {
            String obj = p.getObject();
            // If object is a literal constant, it's a known binding for the subject variable
            if (obj != null && (obj.startsWith("\"") || obj.startsWith("<")
                    || obj.matches("\\d+"))) {
                String subject = p.getSubject();
                if (subject != null && subject.startsWith("?") || subject.startsWith("$")) {
                    bindings.put(subject, obj);
                }
            }
        }
        return bindings;
    }
}

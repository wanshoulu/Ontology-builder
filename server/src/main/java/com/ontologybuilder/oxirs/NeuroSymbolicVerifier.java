package com.ontologybuilder.oxirs;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Neuro-symbolic SPARQL verifier: validates LLM-generated SPARQL queries
 * against the OWL TBox (schema) for semantic correctness.
 *
 * Verification dimensions:
 * - Property type matching (ObjectProperty vs DataProperty)
 * - Domain/range checking
 * - Graph connectivity
 * - Disjoint class detection
 * - Functional property conflicts
 */
public class NeuroSymbolicVerifier {

    private static final Logger log = LoggerFactory.getLogger(NeuroSymbolicVerifier.class);

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final Pattern TRIPLE_PATTERN = Pattern.compile(
            "([?$]\\w+|\\{[^}]*\\}|<[^>]*>|[\\w]+:[\\w]+)\\s+"
                    + "([?$]\\w+|<[^>]*>|[\\w]+:[\\w]+|a)\\s+"
                    + "([?$]\\w+|<[^>]*>|[\\w]+:[\\w]+|\"[^\"]*\"(?:@\\w+|\\^\\^<[^>]*>)?|\\d+(?:\\.\\d+)?)");

    /**
     * Verify a SPARQL query against the OWL TBox.
     *
     * @param sparql       the SPARQL query to verify
     * @param owlOntology  the OWL ontology (TBox)
     * @param aboxSample   optional ABox sample for known-value checking
     * @return verification result with issues and TBox context
     */
    public VerificationResult verify(String sparql, OWLOntology owlOntology, Model aboxSample) {
        VerificationResult result = new VerificationResult();

        try {
            Query query = QueryFactory.create(sparql);
            if (!query.isSelectType()) {
                result.addWarning("query_type", "Only SELECT queries are verified; got " + query.getQueryType(),
                        "query type");
                return result;
            }

            // Extract triple patterns from WHERE clause
            List<ParsedTriple> triples = extractTriplePatterns(sparql);
            if (triples.isEmpty()) {
                result.addWarning("no_patterns", "No triple patterns found in WHERE clause", "WHERE");
                return result;
            }

            // Collect schema context
            Set<OWLObjectProperty> objectProps = owlOntology.getObjectPropertiesInSignature();
            Set<OWLDataProperty> dataProps = owlOntology.getDataPropertiesInSignature();
            Set<OWLClass> allClasses = owlOntology.getClassesInSignature();

            // Build IRI → class/property index
            Map<String, OWLClass> classIndex = new HashMap<>();
            for (OWLClass cls : allClasses) {
                classIndex.put(cls.getIRI().toString(), cls);
            }

            Map<String, OWLObjectProperty> objPropIndex = new HashMap<>();
            for (OWLObjectProperty prop : objectProps) {
                objPropIndex.put(prop.getIRI().toString(), prop);
            }

            Map<String, OWLDataProperty> dataPropIndex = new HashMap<>();
            for (OWLDataProperty prop : dataProps) {
                dataPropIndex.put(prop.getIRI().toString(), prop);
            }

            // 1. Property type matching
            verifyPropertyTypes(result, triples, objPropIndex, dataPropIndex);

            // 2. Domain/range checking
            verifyDomainRange(result, triples, owlOntology, objPropIndex, dataPropIndex, classIndex);

            // 3. Graph connectivity
            verifyConnectivity(result, triples);

            // 4. Disjoint class detection
            verifyDisjointClasses(result, triples, owlOntology, classIndex);

            // 5. Functional property conflicts
            verifyFunctionalProperties(result, triples, owlOntology, objPropIndex, dataPropIndex);

            // Build relevant TBox context for LLM correction
            result.setSchemaContext(buildSchemaContext(triples, owlOntology, objPropIndex, dataPropIndex, classIndex));

        } catch (Exception e) {
            log.warn("[NeSyS] SPARQL verification failed with exception: {}", e.getMessage());
            result.addError("parse_error", "Failed to parse or verify SPARQL: " + e.getMessage(), "entire query");
        }

        return result;
    }

    private void verifyPropertyTypes(VerificationResult result, List<ParsedTriple> triples,
                                       Map<String, OWLObjectProperty> objProps,
                                       Map<String, OWLDataProperty> dataProps) {
        for (ParsedTriple t : triples) {
            String pred = t.getPredicateUri();
            if (pred == null || pred.equals("a") || pred.equals(RDF_TYPE)) continue;

            boolean isObjProp = objProps.containsKey(pred);
            boolean isDataProp = dataProps.containsKey(pred);

            if (!isObjProp && !isDataProp) {
                result.addWarning("unknown_property",
                        "Property not found in TBox: " + shortName(pred),
                        t.toString());
                continue;
            }

            // ObjectProperty's object must be IRI or variable, not literal
            if (isObjProp && t.isLiteralObject()) {
                result.addError("property_type_mismatch",
                        shortName(pred) + " is an ObjectProperty but used with literal \"" + t.getObject() + "\"",
                        t.toString());
            }

            // DataProperty's object should be literal or variable, not IRI
            if (isDataProp && t.isIriObject()) {
                result.addWarning("property_type_mismatch",
                        shortName(pred) + " is a DataProperty but used with IRI <" + t.getObject() + ">",
                        t.toString());
            }
        }
    }

    private void verifyDomainRange(VerificationResult result, List<ParsedTriple> triples,
                                     OWLOntology ontology,
                                     Map<String, OWLObjectProperty> objProps,
                                     Map<String, OWLDataProperty> dataProps,
                                     Map<String, OWLClass> classIndex) {
        // For each triple, check if subject class is constrained by domain
        for (ParsedTriple t : triples) {
            String pred = t.getPredicateUri();
            if (pred == null || pred.equals("a") || pred.equals(RDF_TYPE)) continue;

            // Look up domain constraints from the ontology
            OWLProperty prop = findProperty(pred, objProps, dataProps, ontology);
            if (prop == null) continue;

            // Check domain for known subject
            if (t.getSubjectUri() != null && classIndex.containsKey(t.getSubjectUri())) {
                // Subject is a known class IRI - check if it's compatible with the property's domain
                Set<OWLClassExpression> domains = getDomainsForProperty(prop, ontology);
                for (OWLClassExpression domain : domains) {
                    if (domain.isOWLClass() && classIndex.containsKey(domain.asOWLClass().getIRI().toString())) {
                        if (!t.getSubjectUri().equals(domain.asOWLClass().getIRI().toString())) {
                            result.addWarning("domain_mismatch",
                                    shortName(pred) + " domain is " + shortName(domain.asOWLClass().getIRI().toString())
                                            + " but subject class is " + shortName(t.getSubjectUri()),
                                    t.toString());
                        }
                    }
                }
            }
        }
    }

    private void verifyConnectivity(VerificationResult result, List<ParsedTriple> triples) {
        // Check that all triple patterns form a connected graph via shared variables
        if (triples.size() <= 1) return;

        Set<String> connectedVariables = new HashSet<>();
        Set<String> allVariables = new HashSet<>();

        for (ParsedTriple t : triples) {
            if (t.isVariable(t.getSubject())) connectedVariables.add(t.getSubject());
            if (t.isVariable(t.getSubject())) allVariables.add(t.getSubject());
            if (t.isVariable(t.getPredicate())) allVariables.add(t.getPredicate());
            if (t.isVariable(t.getObject())) allVariables.add(t.getObject());
        }

        // Check connectivity via shared variables across triples
        // Simple check: triples that share at least one variable are connected
        if (triples.size() > 1) {
            boolean[] connected = new boolean[triples.size()];
            connected[0] = true;
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < triples.size(); i++) {
                    if (connected[i]) continue;
                    for (int j = 0; j < triples.size(); j++) {
                        if (!connected[j]) continue;
                        if (shareVariable(triples.get(i), triples.get(j))) {
                            connected[i] = true;
                            changed = true;
                            break;
                        }
                    }
                }
            }
            for (int i = 0; i < connected.length; i++) {
                if (!connected[i]) {
                    result.addError("disconnected_graph",
                            "Triple pattern is disconnected from the rest of the query: "
                                    + triples.get(i),
                            triples.get(i).toString());
                }
            }
        }
    }

    private void verifyDisjointClasses(VerificationResult result, List<ParsedTriple> triples,
                                        OWLOntology ontology, Map<String, OWLClass> classIndex) {
        // Build variable → set of class constraints from rdf:type patterns
        Map<String, Set<String>> varClasses = new HashMap<>();
        for (ParsedTriple t : triples) {
            if (RDF_TYPE.equals(t.getPredicateUri()) || "a".equals(t.getPredicate())) {
                String var = t.getSubject();
                String clsIri = t.getObject();
                if (t.isVariable(var) && !t.isVariable(clsIri)) {
                    varClasses.computeIfAbsent(var, k -> new HashSet<>()).add(clsIri);
                }
            }
        }

        // Check each variable's classes against disjoint axioms
        for (Map.Entry<String, Set<String>> entry : varClasses.entrySet()) {
            List<String> classes = new ArrayList<>(entry.getValue());
            for (int i = 0; i < classes.size(); i++) {
                for (int j = i + 1; j < classes.size(); j++) {
                    if (areDisjoint(classes.get(i), classes.get(j), ontology, classIndex)) {
                        result.addError("disjoint_classes",
                                "Variable " + entry.getKey() + " is typed as both "
                                        + shortName(classes.get(i)) + " and " + shortName(classes.get(j))
                                        + " which are declared disjoint",
                                entry.getKey() + " rdf:type " + shortName(classes.get(i))
                                        + ", " + shortName(classes.get(j)));
                    }
                }
            }
        }
    }

    private void verifyFunctionalProperties(VerificationResult result, List<ParsedTriple> triples,
                                              OWLOntology ontology,
                                              Map<String, OWLObjectProperty> objProps,
                                              Map<String, OWLDataProperty> dataProps) {
        // Check: if property P is functional and appears twice with same subject but different objects
        Map<String, Map<String, Set<String>>> subjectPredObj = new HashMap<>();

        for (ParsedTriple t : triples) {
            String pred = t.getPredicateUri();
            if (pred == null || pred.equals("a") || pred.equals(RDF_TYPE)) continue;

            boolean isFunctional = isFunctionalProperty(pred, ontology, objProps, dataProps);
            if (isFunctional && !t.isVariable(t.getSubject())) {
                subjectPredObj.computeIfAbsent(t.getSubject(), k -> new HashMap<>())
                        .computeIfAbsent(pred, k -> new HashSet<>())
                        .add(t.getObject());
            }
        }

        for (Map.Entry<String, Map<String, Set<String>>> subjEntry : subjectPredObj.entrySet()) {
            for (Map.Entry<String, Set<String>> predEntry : subjEntry.getValue().entrySet()) {
                if (predEntry.getValue().size() > 1) {
                    result.addWarning("functional_property_conflict",
                            "Functional property " + shortName(predEntry.getKey())
                                    + " used with multiple values for same subject " + subjEntry.getKey(),
                            predEntry.getKey());
                }
            }
        }
    }

    private boolean shareVariable(ParsedTriple t1, ParsedTriple t2) {
        for (String v1 : t1.getVariables()) {
            for (String v2 : t2.getVariables()) {
                if (v1.equals(v2)) return true;
            }
        }
        return false;
    }

    private boolean areDisjoint(String classUri1, String classUri2,
                                 OWLOntology ontology, Map<String, OWLClass> classIndex) {
        OWLClass cls1 = classIndex.get(classUri1);
        OWLClass cls2 = classIndex.get(classUri2);
        if (cls1 == null || cls2 == null) return false;

        for (OWLDisjointClassesAxiom ax : ontology.getDisjointClassesAxioms(cls1)) {
            if (ax.getClassExpressions().stream().anyMatch(
                    ce -> ce.isOWLClass() && ce.asOWLClass().equals(cls2))) {
                return true;
            }
        }
        return false;
    }

    private boolean isFunctionalProperty(String propUri, OWLOntology ontology,
                                           Map<String, OWLObjectProperty> objProps,
                                           Map<String, OWLDataProperty> dataProps) {
        if (objProps.containsKey(propUri)) {
            return !ontology.getFunctionalObjectPropertyAxioms(objProps.get(propUri)).isEmpty();
        }
        if (dataProps.containsKey(propUri)) {
            return !ontology.getFunctionalDataPropertyAxioms(dataProps.get(propUri)).isEmpty();
        }
        return false;
    }

    private Set<OWLClassExpression> getDomainsForProperty(OWLProperty prop, OWLOntology ontology) {
        if (prop.isOWLObjectProperty()) {
            return EntitySearcher.getDomains(prop.asOWLObjectProperty(), ontology)
                    .collect(Collectors.toSet());
        } else if (prop.isOWLDataProperty()) {
            return EntitySearcher.getDomains(prop.asOWLDataProperty(), ontology)
                    .collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }

    private OWLProperty findProperty(String uri, Map<String, OWLObjectProperty> objProps,
                                          Map<String, OWLDataProperty> dataProps,
                                          OWLOntology ontology) {
        if (objProps.containsKey(uri)) return objProps.get(uri);
        if (dataProps.containsKey(uri)) return dataProps.get(uri);
        // Try with angle brackets stripped
        String cleanUri = uri.replaceAll("^<|>$", "");
        if (objProps.containsKey(cleanUri)) return objProps.get(cleanUri);
        if (dataProps.containsKey(cleanUri)) return dataProps.get(cleanUri);
        return null;
    }

    private String buildSchemaContext(List<ParsedTriple> triples, OWLOntology ontology,
                                        Map<String, OWLObjectProperty> objProps,
                                        Map<String, OWLDataProperty> dataProps,
                                        Map<String, OWLClass> classIndex) {
        StringBuilder sb = new StringBuilder();
        Set<String> mentionedPredicates = new HashSet<>();
        Set<String> mentionedClasses = new HashSet<>();

        for (ParsedTriple t : triples) {
            String pred = t.getPredicateUri();
            if (pred != null && !pred.equals("a")) {
                mentionedPredicates.add(pred);
            }
            if (RDF_TYPE.equals(pred) || "a".equals(pred)) {
                mentionedClasses.add(t.getObject());
            }
        }

        sb.append("Relevant schema context:\n");
        for (String predUri : mentionedPredicates) {
            OWLProperty prop = findProperty(predUri, objProps, dataProps, ontology);
            if (prop == null) continue;

            sb.append("- Property ").append(shortName(predUri));
            if (prop.isOWLObjectProperty()) {
                sb.append(" (ObjectProperty)");
            } else if (prop.isOWLDataProperty()) {
                sb.append(" (DataProperty)");
            }
            sb.append("\n");

            // Domain
            Set<OWLClassExpression> domains = getDomainsForProperty(prop, ontology);
            if (!domains.isEmpty()) {
                sb.append("  domain: ");
                sb.append(domains.stream()
                        .map(d -> d.isOWLClass() ? shortName(d.asOWLClass().getIRI().toString()) : d.toString())
                        .collect(Collectors.joining(", ")));
                sb.append("\n");
            }

            // Range
            if (prop.isOWLObjectProperty()) {
                Set<OWLClassExpression> ranges = EntitySearcher.getRanges(
                        prop.asOWLObjectProperty(), ontology).collect(Collectors.toSet());
                if (!ranges.isEmpty()) {
                    sb.append("  range: ");
                    sb.append(ranges.stream()
                            .map(r -> r.isOWLClass() ? shortName(r.asOWLClass().getIRI().toString()) : r.toString())
                            .collect(Collectors.joining(", ")));
                    sb.append("\n");
                }
            } else if (prop.isOWLDataProperty()) {
                Set<OWLDataRange> ranges = EntitySearcher.getRanges(
                        prop.asOWLDataProperty(), ontology).collect(Collectors.toSet());
                if (!ranges.isEmpty()) {
                    sb.append("  range: ");
                    sb.append(ranges.stream().map(Object::toString)
                            .collect(Collectors.joining(", ")));
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }

    private List<ParsedTriple> extractTriplePatterns(String sparql) {
        List<ParsedTriple> triples = new ArrayList<>();
        Matcher m = TRIPLE_PATTERN.matcher(sparql);
        while (m.find()) {
            String subj = m.group(1);
            String pred = m.group(2);
            String obj = m.group(3);
            // Skip SERVICE, BIND, VALUES patterns
            if (subj.startsWith("SERVICE") || subj.startsWith("BIND") || subj.startsWith("VALUES")) continue;
            if (pred.startsWith("<") && pred.endsWith(">")) {
                pred = pred.substring(1, pred.length() - 1);
            }
            if ("a".equals(pred)) {
                pred = RDF_TYPE;
            }
            triples.add(new ParsedTriple(subj, pred, obj));
        }
        return triples;
    }

    private static String shortName(String iri) {
        if (iri == null) return "null";
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int sep = Math.max(hash, slash);
        return sep >= 0 && sep < iri.length() - 1 ? iri.substring(sep + 1) : iri;
    }

    // ============================================================
    // Parsed triple representation
    // ============================================================

    // ============================================================
    // Triple-level validation for knowledge update
    // ============================================================

    /**
     * Validate a set of proposed triple facts against the OWL TBox.
     * Used by the knowledge update flow to check facts before writing to TDB2.
     *
     * @param facts        the proposed triples (subject IRI, predicate IRI, object value)
     * @param owlOntology  the OWL ontology (TBox)
     * @return verification result with issues
     */
    public VerificationResult validateTripleFacts(List<TripleFact> facts, OWLOntology owlOntology) {
        VerificationResult result = new VerificationResult();

        try {
            Set<OWLObjectProperty> objectProps = owlOntology.getObjectPropertiesInSignature();
            Set<OWLDataProperty> dataProps = owlOntology.getDataPropertiesInSignature();
            Set<OWLClass> allClasses = owlOntology.getClassesInSignature();

            Map<String, OWLObjectProperty> objPropIndex = new HashMap<>();
            for (OWLObjectProperty prop : objectProps) {
                objPropIndex.put(prop.getIRI().toString(), prop);
            }

            Map<String, OWLDataProperty> dataPropIndex = new HashMap<>();
            for (OWLDataProperty prop : dataProps) {
                dataPropIndex.put(prop.getIRI().toString(), prop);
            }

            Map<String, OWLClass> classIndex = new HashMap<>();
            for (OWLClass cls : allClasses) {
                classIndex.put(cls.getIRI().toString(), cls);
            }

            for (TripleFact fact : facts) {
                String predIri = fact.getPredicateIri();
                if (predIri == null || predIri.equals(RDF_TYPE)) continue;

                // 1. Check property existence
                boolean isObjProp = objPropIndex.containsKey(predIri);
                boolean isDataProp = dataPropIndex.containsKey(predIri);

                if (!isObjProp && !isDataProp) {
                    result.addWarning("unknown_property",
                            "Property not found in TBox: " + shortName(predIri),
                            fact.toString());
                    continue;
                }

                // 2. Check property type vs object type
                if (isObjProp && fact.isLiteralObject()) {
                    result.addError("property_type_mismatch",
                            shortName(predIri) + " is an ObjectProperty but used with literal \"" + fact.getObjectValue() + "\"",
                            fact.toString());
                }
                if (isDataProp && fact.isIriObject()) {
                    result.addWarning("property_type_mismatch",
                            shortName(predIri) + " is a DataProperty but used with IRI <" + fact.getObjectValue() + ">",
                            fact.toString());
                }

                // 3. Check functional property conflicts (duplicate value for functional property)
                if (isFunctionalProperty(predIri, owlOntology, objPropIndex, dataPropIndex)) {
                    result.addWarning("functional_property",
                            "Adding value to functional property " + shortName(predIri)
                                    + ". Existing values may be overwritten.",
                            fact.toString());
                }

                // 4. Check domain (if subject has a known rdf:type)
                OWLProperty prop = findProperty(predIri, objPropIndex, dataPropIndex, owlOntology);
                if (prop != null && fact.getSubjectClassIri() != null) {
                    String subjectClass = fact.getSubjectClassIri();
                    Set<OWLClassExpression> domains = getDomainsForProperty(prop, owlOntology);
                    boolean domainMatch = false;
                    for (OWLClassExpression domain : domains) {
                        if (domain.isOWLClass()
                                && domain.asOWLClass().getIRI().toString().equals(subjectClass)) {
                            domainMatch = true;
                            break;
                        }
                    }
                    if (!domainMatch && !domains.isEmpty()) {
                        result.addWarning("domain_mismatch",
                                shortName(predIri) + " domain is "
                                        + domains.stream().map(d -> d.isOWLClass() ? shortName(d.asOWLClass().getIRI().toString()) : d.toString())
                                                .collect(Collectors.joining(", "))
                                        + " but subject class is " + shortName(subjectClass),
                                fact.toString());
                    }
                }
            }

        } catch (Exception e) {
            log.warn("[NeSyS] Triple fact validation failed with exception: {}", e.getMessage());
            result.addError("validation_error", "Failed to validate triple facts: " + e.getMessage(), "entire update");
        }

        return result;
    }

    // ============================================================
    // Triple fact representation
    // ============================================================

    public static class TripleFact {
        private final String subjectIri;
        private final String predicateIri;
        private final String objectValue;   // literal value or IRI string (without brackets)
        private final String objectType;    // "literal", "iri", or null
        private final String subjectClassIri; // optional, known rdf:type of subject

        public TripleFact(String subjectIri, String predicateIri, String objectValue,
                          String objectType, String subjectClassIri) {
            this.subjectIri = subjectIri;
            this.predicateIri = predicateIri;
            this.objectValue = objectValue;
            this.objectType = objectType;
            this.subjectClassIri = subjectClassIri;
        }

        public String getSubjectIri() { return subjectIri; }
        public String getPredicateIri() { return predicateIri; }
        public String getObjectValue() { return objectValue; }
        public String getObjectType() { return objectType; }
        public String getSubjectClassIri() { return subjectClassIri; }

        boolean isLiteralObject() {
            return "literal".equals(objectType);
        }

        boolean isIriObject() {
            return "iri".equals(objectType);
        }

        @Override
        public String toString() {
            if ("iri".equals(objectType)) {
                return "<" + subjectIri + "> <" + predicateIri + "> <" + objectValue + "> .";
            } else {
                return "<" + subjectIri + "> <" + predicateIri + "> \"" + objectValue + "\" .";
            }
        }

        /**
         * Convert this triple fact to a TTL line.
         */
        public String toTtlLine() {
            if ("iri".equals(objectType)) {
                return "<" + subjectIri + "> <" + predicateIri + "> <" + objectValue + "> .";
            } else {
                // Escape quotes in literal value
                String escaped = objectValue.replace("\\", "\\\\").replace("\"", "\\\"");
                return "<" + subjectIri + "> <" + predicateIri + "> \"" + escaped + "\" .";
            }
        }
    }

    static class ParsedTriple {
        private final String subject;
        private final String predicate;
        private final String object;

        ParsedTriple(String subject, String predicate, String object) {
            this.subject = subject;
            this.predicate = predicate;
            this.object = object;
        }

        String getSubject() { return subject; }
        String getPredicate() { return predicate; }
        String getObject() { return object; }

        String getPredicateUri() {
            if (predicate == null) return null;
            if (predicate.startsWith("<") && predicate.endsWith(">")) {
                return predicate.substring(1, predicate.length() - 1);
            }
            return predicate;
        }

        String getSubjectUri() {
            if (subject == null || isVariable(subject)) return null;
            if (subject.startsWith("<") && subject.endsWith(">")) {
                return subject.substring(1, subject.length() - 1);
            }
            return subject;
        }

        boolean isVariable(String s) {
            return s != null && (s.startsWith("?") || s.startsWith("$"));
        }

        boolean isLiteralObject() {
            return object != null && object.startsWith("\"");
        }

        boolean isIriObject() {
            return object != null && object.startsWith("<") && object.endsWith(">");
        }

        Set<String> getVariables() {
            Set<String> vars = new HashSet<>();
            if (isVariable(subject)) vars.add(subject);
            if (isVariable(predicate)) vars.add(predicate);
            if (isVariable(object)) vars.add(object);
            return vars;
        }

        @Override
        public String toString() {
            return subject + " " + predicate + " " + object + " .";
        }
    }
}

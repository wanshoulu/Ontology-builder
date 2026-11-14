package com.ontologybuilder.oxirs;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzes the structure of SWRL rules from a Jena Model:
 * - Extracts body/head predicate URIs
 * - Classifies rule type (structural closure vs conditional derivation vs builtin)
 * - Estimates expansion ratio
 */
public class SwrlRuleAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SwrlRuleAnalyzer.class);

    private static final String SWRL_NS = "http://www.w3.org/2003/11/swrl#";
    private static final String SWRLB_NS = "http://www.w3.org/2003/11/swrlb#";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";

    // Structural closure predicates (always FC)
    private static final Set<String> STRUCTURAL_PREDICATES = Set.of(
            RDFS_NS + "subClassOf",
            OWL_NS + "equivalentClass",
            OWL_NS + "equivalentProperty",
            OWL_NS + "sameAs",
            OWL_NS + "TransitiveProperty",
            OWL_NS + "SymmetricProperty",
            OWL_NS + "inverseOf"
    );

    private final Resource swrlImpType;
    private final Resource classAtomType;
    private final Resource dataPropAtomType;
    private final Resource indPropAtomType;
    private final Resource builtinAtomType;
    private final Property swrlBody;
    private final Property swrlHead;
    private final Property classPred;
    private final Property propertyPred;
    private final Property builtinAttr;
    private final Property rdfFirst;
    private final Property rdfRest;
    private final Resource rdfNil;

    public SwrlRuleAnalyzer() {
        this.swrlImpType = ResourceFactory.createResource(SWRL_NS + "Imp");
        this.classAtomType = ResourceFactory.createResource(SWRL_NS + "ClassAtom");
        this.dataPropAtomType = ResourceFactory.createResource(SWRL_NS + "DatavaluedPropertyAtom");
        this.indPropAtomType = ResourceFactory.createResource(SWRL_NS + "IndividualPropertyAtom");
        this.builtinAtomType = ResourceFactory.createResource(SWRL_NS + "BuiltinAtom");
        this.swrlBody = ResourceFactory.createProperty(SWRL_NS + "body");
        this.swrlHead = ResourceFactory.createProperty(SWRL_NS + "head");
        this.classPred = ResourceFactory.createProperty(SWRL_NS + "classPredicate");
        this.propertyPred = ResourceFactory.createProperty(SWRL_NS + "propertyPredicate");
        this.builtinAttr = ResourceFactory.createProperty(SWRL_NS + "builtin");
        this.rdfFirst = ResourceFactory.createProperty(RDF_NS + "first");
        this.rdfRest = ResourceFactory.createProperty(RDF_NS + "rest");
        this.rdfNil = ResourceFactory.createResource(RDF_NS + "nil");
    }

    /**
     * Analyzes all SWRL Imps in the model and returns rule profiles.
     */
    public List<RuleProfile> analyze(Model model) {
        List<Resource> swrlImps = model.listResourcesWithProperty(RDF.type, swrlImpType).toList();
        List<RuleProfile> profiles = new ArrayList<>();

        for (Resource swrlImp : swrlImps) {
            try {
                RuleProfile profile = analyzeSingle(swrlImp, model);
                if (profile != null) {
                    profiles.add(profile);
                }
            } catch (Exception e) {
                log.warn("[SwrlAnalyzer] Failed to analyze rule {}: {}",
                        swrlImp.getURI(), e.getMessage());
            }
        }
        log.info("[SwrlAnalyzer] Analyzed {} SWRL rules", profiles.size());
        return profiles;
    }

    private RuleProfile analyzeSingle(Resource swrlImp, Model model) {
        String ruleId = swrlImp.getURI() != null ? swrlImp.getURI() : swrlImp.getId().toString();
        String ruleName = extractSwrlRuleName(swrlImp);

        // Extract SWRL text from label or rdfs:comment
        String swrlText = extractSwrlText(swrlImp);

        List<Resource> bodyAtoms = traverseRdfList(model, swrlImp.getProperty(swrlBody));
        List<Resource> headAtoms = traverseRdfList(model, swrlImp.getProperty(swrlHead));

        Set<String> bodyPredicates = new HashSet<>();
        Set<String> headPredicates = new HashSet<>();
        boolean hasBuiltin = false;

        for (Resource atom : bodyAtoms) {
            if (model.contains(atom, RDF.type, builtinAtomType)) {
                hasBuiltin = true;
                Statement builtinStmt = atom.getProperty(builtinAttr);
                if (builtinStmt != null && builtinStmt.getObject().isURIResource()) {
                    bodyPredicates.add(builtinStmt.getObject().asResource().getURI());
                }
            } else {
                extractPredicate(model, atom, bodyPredicates);
            }
        }

        for (Resource atom : headAtoms) {
            extractPredicate(model, atom, headPredicates);
        }

        RuleProfile.RuleType type = classifyRuleType(bodyPredicates, headPredicates);
        RuleProfile profile = new RuleProfile(ruleId, ruleName, swrlText, type);
        profile.getBodyPredicates().addAll(bodyPredicates);
        profile.getHeadPredicates().addAll(headPredicates);

        if (hasBuiltin) {
            profile.setExpansionRatio(estimateBuiltinExpansion(bodyPredicates, headPredicates, bodyAtoms, model));
        }
        if (type == RuleProfile.RuleType.STRUCTURAL_CLOSURE) {
            profile.setExpansionRatio(1.0);
        }
        if (type == RuleProfile.RuleType.CONDITIONAL_DERIVATION && !hasBuiltin) {
            profile.setExpansionRatio(estimateConditionalExpansion(bodyPredicates, headPredicates, model));
        }

        return profile;
    }

    private void extractPredicate(Model model, Resource atom, Set<String> predicates) {
        if (model.contains(atom, RDF.type, classAtomType)) {
            Statement stmt = atom.getProperty(classPred);
            if (stmt != null && stmt.getObject().isURIResource()) {
                predicates.add(stmt.getObject().asResource().getURI());
            }
        } else if (model.contains(atom, RDF.type, dataPropAtomType)
                || model.contains(atom, RDF.type, indPropAtomType)) {
            Statement stmt = atom.getProperty(propertyPred);
            if (stmt != null && stmt.getObject().isURIResource()) {
                predicates.add(stmt.getObject().asResource().getURI());
            }
        }
    }

    private RuleProfile.RuleType classifyRuleType(Set<String> bodyPreds, Set<String> headPreds) {
        boolean bodyHasStructural = bodyPreds.stream().anyMatch(STRUCTURAL_PREDICATES::contains);
        boolean headHasStructural = headPreds.stream().anyMatch(STRUCTURAL_PREDICATES::contains);

        if (bodyHasStructural || headHasStructural) {
            return RuleProfile.RuleType.STRUCTURAL_CLOSURE;
        }
        if (!bodyPreds.isEmpty() && bodyPreds.stream().anyMatch(p -> p.startsWith(SWRLB_NS))) {
            return RuleProfile.RuleType.BUILTIN;
        }
        return RuleProfile.RuleType.CONDITIONAL_DERIVATION;
    }

    private double estimateConditionalExpansion(Set<String> bodyPreds, Set<String> headPreds, Model model) {
        // Conservative estimate: if head has more predicates than body, expansion > 1
        if (headPreds.isEmpty() || bodyPreds.isEmpty()) return 1.0;
        return (double) headPreds.size() / Math.max(1, bodyPreds.size());
    }

    private double estimateBuiltinExpansion(Set<String> bodyPreds, Set<String> headPreds,
                                             List<Resource> bodyAtoms, Model model) {
        // Builtins like swrlb:greaterThan typically don't expand
        // Builtins like swrlb:add/swrlb:multiply can be expensive
        for (Resource atom : bodyAtoms) {
            Statement builtinStmt = atom.getProperty(builtinAttr);
            if (builtinStmt != null && builtinStmt.getObject().isURIResource()) {
                String builtinUri = builtinStmt.getObject().asResource().getURI();
                if (builtinUri.contains("greaterThan") || builtinUri.contains("lessThan")
                        || builtinUri.contains("equal") || builtinUri.contains("notEqual")) {
                    return 1.0;
                }
                if (builtinUri.contains("add") || builtinUri.contains("multiply")
                        || builtinUri.contains("concat")) {
                    return 5.0;
                }
            }
        }
        return 3.0;
    }

    private String extractSwrlRuleName(Resource swrlImp) {
        Statement labelStmt = swrlImp.getProperty(RDFS.label);
        if (labelStmt != null) return labelStmt.getObject().toString();
        Statement commentStmt = swrlImp.getProperty(RDFS.comment);
        if (commentStmt != null) return commentStmt.getObject().toString();
        if (swrlImp.getURI() != null) {
            String uri = swrlImp.getURI();
            return uri.substring(uri.lastIndexOf('#') + 1);
        }
        return swrlImp.getId().toString();
    }

    private String extractSwrlText(Resource swrlImp) {
        Statement labelStmt = swrlImp.getProperty(RDFS.label);
        if (labelStmt != null) return labelStmt.getObject().toString();
        if (swrlImp.getURI() != null) return swrlImp.getURI();
        return "";
    }

    private List<Resource> traverseRdfList(Model model, Statement listStmt) {
        List<Resource> items = new ArrayList<>();
        if (listStmt == null) return items;
        Resource current = listStmt.getResource();
        while (current != null && !current.equals(rdfNil)) {
            Statement firstStmt = current.getProperty(rdfFirst);
            if (firstStmt != null && firstStmt.getObject().isResource()) {
                items.add(firstStmt.getObject().asResource());
            }
            Statement restStmt = current.getProperty(rdfRest);
            current = (restStmt != null && restStmt.getObject().isResource())
                    ? restStmt.getObject().asResource() : null;
        }
        return items;
    }
}

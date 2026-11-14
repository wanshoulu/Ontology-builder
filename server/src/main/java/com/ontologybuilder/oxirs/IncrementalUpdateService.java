package com.ontologybuilder.oxirs;

import com.ontologybuilder.oxirs.grpc.ForwardChainResponse;
import com.ontologybuilder.oxirs.grpc.Triple;
import org.apache.jena.rdf.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Coordinates incremental reasoning: when new triples are loaded into TDB2,
 * uses OxiRS forwardChain (incremental_mode=true) to compute FC inferences
 * on delta triples without full rematerialization.
 *
 * Falls back to empty result on failure — caller is responsible for
 * triggering Jena full rematerialization as a safety net.
 */
public class IncrementalUpdateService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalUpdateService.class);

    private final OxiRSClient oxirsClient;
    private final List<com.ontologybuilder.oxirs.grpc.Rule> fcRules;

    public IncrementalUpdateService(OxiRSClient oxirsClient,
                                    List<com.ontologybuilder.oxirs.grpc.Rule> fcRules) {
        this.oxirsClient = oxirsClient;
        this.fcRules = fcRules != null ? fcRules : Collections.emptyList();
    }

    /**
     * Run OxiRS forwardChain in incremental mode on new triples.
     *
     * @param newTriples the triples just added to TDB2
     * @param graphUri   the named graph URI (ontology ID)
     * @return inferred triples as a Jena Model (empty if OxiRS fails or produces nothing)
     */
    public Model onTriplesAdded(Model newTriples, String graphUri) {
        if (!oxirsClient.isEnabled() || fcRules.isEmpty()) {
            return ModelFactory.createDefaultModel();
        }

        List<Triple> grpcTriples = convertToGrpcTriples(newTriples);
        if (grpcTriples.isEmpty()) {
            return ModelFactory.createDefaultModel();
        }

        try {
            ForwardChainResponse resp = oxirsClient.forwardChain(
                    grpcTriples, fcRules, 100, true);
            if (resp.getSuccess() && resp.getInferredTriplesCount() > 0) {
                log.info("[Incremental] forwardChain(incremental=true): {} delta → {} inferred for {}",
                        grpcTriples.size(), resp.getInferredTriplesCount(), graphUri);
                return convertFromGrpcTriples(resp.getInferredTriplesList());
            }
            if (!resp.getSuccess()) {
                log.warn("[Incremental] OxiRS forwardChain failed: {}", resp.getErrorMessage());
            }
        } catch (Exception e) {
            log.warn("[Incremental] OxiRS forwardChain exception, will fall back to Jena: {}", e.getMessage());
        }
        return ModelFactory.createDefaultModel();
    }

    /**
     * Notify OxiRS of removed triples.
     */
    public int onTriplesRemoved(Model removedTriples, String graphUri) {
        if (!oxirsClient.isEnabled()) return 0;

        List<Triple> grpcTriples = convertToGrpcTriples(removedTriples);
        if (grpcTriples.isEmpty()) return 0;

        var response = oxirsClient.incrementalRemove(grpcTriples, graphUri);
        if (response.getSuccess()) {
            log.info("[Incremental] Removed {} triples for {}", grpcTriples.size(), graphUri);
            return response.getFcInferredCount();
        }
        return 0;
    }

    // ============================================================
    // Model ↔ gRPC Triple conversion (public for use by ReasoningService)
    // ============================================================

    /**
     * Convert Jena model statements to gRPC Triple messages.
     */
    public static List<Triple> convertToGrpcTriples(Model model) {
        List<Triple> result = new ArrayList<>();
        for (Statement stmt : model.listStatements().toList()) {
            result.add(convertStatement(stmt));
        }
        return result;
    }

    private static Triple convertStatement(Statement stmt) {
        Triple.Builder builder = Triple.newBuilder();

        if (stmt.getSubject().isURIResource()) {
            builder.setSubject(stmt.getSubject().getURI());
        } else {
            builder.setSubject("_:" + stmt.getSubject().getId().getLabelString());
        }

        builder.setPredicate(stmt.getPredicate().getURI());

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

    /**
     * Convert gRPC Triple list back to a Jena Model.
     */
    public static Model convertFromGrpcTriples(List<Triple> triples) {
        Model model = ModelFactory.createDefaultModel();
        for (Triple t : triples) {
            Resource s = model.createResource(t.getSubject());
            Property p = model.createProperty(t.getPredicate());
            RDFNode o;
            if (t.getIsLiteral()) {
                if (!t.getLanguage().isEmpty()) {
                    o = model.createLiteral(t.getObject(), t.getLanguage());
                } else if (!t.getDatatype().isEmpty()) {
                    o = model.createTypedLiteral(t.getObject(), t.getDatatype());
                } else {
                    o = model.createLiteral(t.getObject());
                }
            } else {
                o = model.createResource(t.getObject());
            }
            model.add(s, p, o);
        }
        return model;
    }
}

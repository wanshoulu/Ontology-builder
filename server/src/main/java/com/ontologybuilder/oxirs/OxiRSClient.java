package com.ontologybuilder.oxirs;

import com.google.protobuf.util.JsonFormat;
import com.ontologybuilder.oxirs.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * High-level client for OxiRS reasoning engine gRPC service.
 * Wraps generated gRPC stubs with business-level methods and error handling.
 */
public class OxiRSClient {

    private static final Logger log = LoggerFactory.getLogger(OxiRSClient.class);
    private static final JsonFormat.Printer JSON_PRINTER = JsonFormat.printer().preservingProtoFieldNames();

    // gRPC deadlines to prevent indefinite hangs
    private static final long FORWARD_CHAIN_DEADLINE_SEC = 600;
    private static final long BACKWARD_CHAIN_DEADLINE_SEC = 5;
    private static final long INCREMENTAL_UPDATE_DEADLINE_SEC = 30;
    private static final long RULE_MGMT_DEADLINE_SEC = 10;
    private static final long HEALTH_CHECK_DEADLINE_SEC = 5;

    private final OxiRSReasonerGrpc.OxiRSReasonerBlockingStub blockingStub;
    private final ManagedChannel channel;
    private final boolean enabled;

    public OxiRSClient(ManagedChannel channel, boolean enabled) {
        this.channel = channel;
        this.enabled = enabled;
        this.blockingStub = enabled ? OxiRSReasonerGrpc.newBlockingStub(channel) : null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ============================================================
    // Forward Chain
    // ============================================================

    public ForwardChainResponse forwardChain(List<Triple> baseTriples, List<Rule> fcRules,
                                              int maxIterations, boolean incrementalMode) {
        checkEnabled("forwardChain");
        ForwardChainRequest request = ForwardChainRequest.newBuilder()
                .addAllBaseTriples(baseTriples)
                .addAllFcRules(fcRules)
                .setMaxIterations(maxIterations > 0 ? maxIterations : 100)
                .setIncrementalMode(incrementalMode)
                .build();
        try {
            ForwardChainResponse resp = blockingStub
                    .withDeadlineAfter(FORWARD_CHAIN_DEADLINE_SEC, TimeUnit.SECONDS)
                    .forwardChain(request);
            log.debug("ForwardChain completed: iterations={}, inferred={}, elapsedMs={}",
                    resp.getIterations(), resp.getInferredTriplesCount(), resp.getElapsedMs());
            return resp;
        } catch (StatusRuntimeException e) {
            log.warn("ForwardChain gRPC failed: {}", e.getMessage());
            return ForwardChainResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("gRPC error: " + e.getMessage())
                    .build();
        }
    }

    // ============================================================
    // Backward Chain
    // ============================================================

    public BackwardChainResponse backwardChain(List<String> goals, List<Triple> baseFacts,
                                                List<Rule> bcRules, Map<String, String> knownBindings,
                                                int maxDepth, int timeoutMs) {
        checkEnabled("backwardChain");
        BackwardChainRequest.Builder builder = BackwardChainRequest.newBuilder()
                .addAllGoals(goals)
                .addAllBaseFacts(baseFacts)
                .addAllBcRules(bcRules)
                .putAllKnownBindings(knownBindings != null ? knownBindings : Map.of())
                .setMaxDepth(maxDepth > 0 ? maxDepth : 10)
                .setTimeoutMs(timeoutMs > 0 ? timeoutMs : 500);
        try {
            BackwardChainResponse resp = blockingStub
                    .withDeadlineAfter(BACKWARD_CHAIN_DEADLINE_SEC, TimeUnit.SECONDS)
                    .backwardChain(builder.build());
            log.debug("BackwardChain completed: results={}, elapsedMs={}",
                    resp.getResultsCount(), resp.getElapsedMs());
            return resp;
        } catch (StatusRuntimeException e) {
            log.warn("BackwardChain gRPC failed: {}", e.getMessage());
            return BackwardChainResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("gRPC error: " + e.getMessage())
                    .build();
        }
    }

    // ============================================================
    // Incremental Update
    // ============================================================

    public IncrementalUpdateResponse incrementalAdd(List<Triple> triples, String graphUri) {
        return incrementalUpdate(IncrementalUpdateRequest.UpdateType.ADD, triples, graphUri);
    }

    public IncrementalUpdateResponse incrementalRemove(List<Triple> triples, String graphUri) {
        return incrementalUpdate(IncrementalUpdateRequest.UpdateType.REMOVE, triples, graphUri);
    }

    private IncrementalUpdateResponse incrementalUpdate(IncrementalUpdateRequest.UpdateType type,
                                                         List<Triple> triples, String graphUri) {
        checkEnabled("incrementalUpdate");
        IncrementalUpdateRequest request = IncrementalUpdateRequest.newBuilder()
                .setType(type)
                .addAllTriples(triples)
                .setGraphUri(graphUri != null ? graphUri : "")
                .build();
        try {
            IncrementalUpdateResponse resp = blockingStub
                    .withDeadlineAfter(INCREMENTAL_UPDATE_DEADLINE_SEC, TimeUnit.SECONDS)
                    .incrementalUpdate(request);
            log.debug("IncrementalUpdate({}) completed: fcInferred={}, bcAffected={}, elapsedMs={}",
                    type, resp.getFcInferredCount(), resp.getBcRulesAffected(), resp.getElapsedMs());
            return resp;
        } catch (StatusRuntimeException e) {
            log.warn("IncrementalUpdate gRPC failed: {}", e.getMessage());
            return IncrementalUpdateResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("gRPC error: " + e.getMessage())
                    .build();
        }
    }

    // ============================================================
    // Rule Management
    // ============================================================

    public RegisterRulesResponse registerRules(List<Rule> rules, String graphUri) {
        checkEnabled("registerRules");
        RegisterRulesRequest request = RegisterRulesRequest.newBuilder()
                .addAllRules(rules)
                .setGraphUri(graphUri != null ? graphUri : "")
                .build();
        try {
            RegisterRulesResponse resp = blockingStub
                    .withDeadlineAfter(RULE_MGMT_DEADLINE_SEC, TimeUnit.SECONDS)
                    .registerRules(request);
            log.info("Registered {} rules to OxiRS", resp.getRegisteredCount());
            return resp;
        } catch (StatusRuntimeException e) {
            log.warn("RegisterRules gRPC failed: {}", e.getMessage());
            return RegisterRulesResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("gRPC error: " + e.getMessage())
                    .build();
        }
    }

    public RemoveRulesResponse removeRules(List<String> ruleIds, String graphUri) {
        checkEnabled("removeRules");
        RemoveRulesRequest request = RemoveRulesRequest.newBuilder()
                .addAllRuleIds(ruleIds)
                .setGraphUri(graphUri != null ? graphUri : "")
                .build();
        try {
            RemoveRulesResponse resp = blockingStub
                    .withDeadlineAfter(RULE_MGMT_DEADLINE_SEC, TimeUnit.SECONDS)
                    .removeRules(request);
            log.info("Removed {} rules from OxiRS", resp.getRemovedCount());
            return resp;
        } catch (StatusRuntimeException e) {
            log.warn("RemoveRules gRPC failed: {}", e.getMessage());
            return RemoveRulesResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("gRPC error: " + e.getMessage())
                    .build();
        }
    }

    // ============================================================
    // Health Check
    // ============================================================

    public HealthCheckResponse healthCheck() {
        if (!enabled) {
            return HealthCheckResponse.newBuilder().setHealthy(false).build();
        }
        try {
            return blockingStub
                    .withDeadlineAfter(HEALTH_CHECK_DEADLINE_SEC, TimeUnit.SECONDS)
                    .healthCheck(HealthCheckRequest.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            log.warn("OxiRS health check failed: {}", e.getMessage());
            return HealthCheckResponse.newBuilder()
                    .setHealthy(false)
                    .build();
        }
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }

    private void checkEnabled(String method) {
        if (!enabled) {
            throw new IllegalStateException("OxiRS is disabled — " + method + " called but oxirs.enabled=false");
        }
    }
}

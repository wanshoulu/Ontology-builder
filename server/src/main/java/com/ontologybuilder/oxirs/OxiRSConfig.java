package com.ontologybuilder.oxirs;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OxiRSConfig {

    private static final Logger log = LoggerFactory.getLogger(OxiRSConfig.class);

    @Value("${oxirs.enabled:true}")
    private boolean enabled;

    @Value("${oxirs.grpc.host:localhost}")
    private String grpcHost;

    @Value("${oxirs.grpc.port:50051}")
    private int grpcPort;

    @Value("${oxirs.grpc.max-inbound-message-size:10485760}")
    private int maxInboundMessageSize;

    @Bean
    public ManagedChannel oxirsManagedChannel() {
        if (!enabled) {
            log.info("OxiRS is disabled — creating no-op channel");
            return ManagedChannelBuilder.forAddress("localhost", 50051)
                    .maxInboundMessageSize(maxInboundMessageSize)
                    .build();
        }
        log.info("OxiRS gRPC channel configured: {}:{}", grpcHost, grpcPort);
        return ManagedChannelBuilder.forAddress(grpcHost, grpcPort)
                .maxInboundMessageSize(maxInboundMessageSize)
                .usePlaintext()
                .keepAliveTime(60, java.util.concurrent.TimeUnit.SECONDS)
                .keepAliveTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
    }

    @Bean
    public OxiRSClient oxirsClient(ManagedChannel oxirsManagedChannel) {
        return new OxiRSClient(oxirsManagedChannel, enabled);
    }

    @Bean
    public QueryStatsCollector queryStatsCollector() {
        return new QueryStatsCollector(3600000); // 1 hour window
    }

    @Bean
    public RulePromotionScheduler rulePromotionScheduler(QueryStatsCollector statsCollector,
                                                          OxiRSClient oxirsClient) {
        return new RulePromotionScheduler(statsCollector, oxirsClient);
    }
}

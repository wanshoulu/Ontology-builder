package com.ontologybuilder.mdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Catalog change notifier — a no-op sink in this standalone build.
 *
 * In the origin deployment this pushed "ontology catalog changed" events to a
 * downstream metadata portal. Here it only logs; wire in your own transport
 * (MQ / webhook / gRPC) if you need downstream invalidation.
 */
@Component
public class MdpCatalogNotifier {

    private static final Logger log = LoggerFactory.getLogger(MdpCatalogNotifier.class);

    public void notifyRefresh() {
        log.debug("[mdp] ontology catalog changed (no-op: configure a downstream notifier if needed)");
    }
}

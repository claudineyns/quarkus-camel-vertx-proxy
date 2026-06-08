package io.github.claudineyns.proxy;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
public class BulkheadRegistryProducer {

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "proxy.active-requests.per-host-limit", defaultValue = "-1")
    int perHostLimit;

    @Produces
    @ApplicationScoped
    BulkheadRegistry bulkheadRegistry() {
        // When per-host limit is disabled (-1), use MAX_VALUE so tryAcquirePermission() never
        // refuses — bulkhead instances are still created per host for metrics (active calls tagged
        // by host) without imposing a cap.
        final int maxCalls = perHostLimit > 0 ? perHostLimit : Integer.MAX_VALUE;
        final BulkheadConfig config = BulkheadConfig.custom()
            .maxConcurrentCalls(maxCalls)
            .maxWaitDuration(Duration.ZERO)
            .build();
        final BulkheadRegistry registry = BulkheadRegistry.of(config);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
        return registry;
    }
}

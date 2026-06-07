package io.github.claudineyns.proxy;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class ConnectionPressureMonitor {

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "proxy.http.client.pool.max-wait-queue-size", defaultValue = "-1")
    int maxWaitQueueSize;

    @ConfigProperty(name = "proxy.http.client.pool.wait-queue-high-water-mark")
    Optional<Double> highWaterMark;

    void onStart(@Observes final StartupEvent ev) {
        if (maxWaitQueueSize != -1 && highWaterMark.isEmpty()) {
            throw new IllegalStateException(
                "proxy.http.client.pool.wait-queue-high-water-mark is required " +
                "when proxy.http.client.pool.max-wait-queue-size != -1"
            );
        }
    }

    public boolean isUnderPressure() {
        if (maxWaitQueueSize == -1 || highWaterMark.isEmpty()) {
            return false;
        }
        final double threshold = maxWaitQueueSize * highWaterMark.get();
        final double current = meterRegistry.find("vertx.http.client.queue.pending")
            .gauges()
            .stream()
            .mapToDouble(g -> g.value())
            .sum();
        return current > threshold;
    }

    public int getMaxWaitQueueSize() {
        return maxWaitQueueSize;
    }
}

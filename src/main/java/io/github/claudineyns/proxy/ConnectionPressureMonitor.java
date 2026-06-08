package io.github.claudineyns.proxy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ConnectionPressureMonitor {

    private final AtomicInteger activeRequests = new AtomicInteger(0);

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "proxy.active-requests.limit", defaultValue = "-1")
    int limit;

    void onStart(@Observes final StartupEvent ev) {
        Gauge.builder("proxy.active.requests", activeRequests, AtomicInteger::get)
             .description("In-flight proxy requests")
             .register(meterRegistry);
    }

    public boolean tryAcquire() {
        if (limit <= 0) return true;
        // Increment first, then check — avoids a read-then-increment race under concurrent load.
        final int after = activeRequests.incrementAndGet();
        if (after > limit) {
            activeRequests.decrementAndGet();
            return false;
        }
        return true;
    }

    public void release() {
        if (limit > 0) activeRequests.decrementAndGet();
    }

    public boolean isUnderPressure() {
        return limit > 0 && activeRequests.get() >= limit;
    }
}

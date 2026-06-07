package io.github.claudineyns.proxy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ProxyReadinessCheck implements HealthCheck {

    @Inject
    ConnectionPressureMonitor pressureMonitor;

    @Override
    public HealthCheckResponse call() {
        if (pressureMonitor.isUnderPressure()) {
            return HealthCheckResponse.named("proxy-connection-queue")
                .down()
                .withData("reason", "connection queue high-water-mark exceeded")
                .build();
        }
        return HealthCheckResponse.named("proxy-connection-queue").up().build();
    }
}

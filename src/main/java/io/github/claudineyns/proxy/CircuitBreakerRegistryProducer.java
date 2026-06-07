package io.github.claudineyns.proxy;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
public class CircuitBreakerRegistryProducer {

    @ConfigProperty(name = "proxy.http.client.circuit-breaker.sliding-window-size", defaultValue = "10")
    int slidingWindowSize;

    @ConfigProperty(name = "proxy.http.client.circuit-breaker.minimum-number-of-calls", defaultValue = "5")
    int minimumNumberOfCalls;

    @ConfigProperty(name = "proxy.http.client.circuit-breaker.failure-rate-threshold", defaultValue = "50")
    float failureRateThreshold;

    @ConfigProperty(name = "proxy.http.client.circuit-breaker.wait-duration-open-ms", defaultValue = "30000")
    long waitDurationOpenMs;

    @ConfigProperty(name = "proxy.http.client.circuit-breaker.permitted-calls-half-open", defaultValue = "3")
    int permittedCallsHalfOpen;

    @Produces
    @ApplicationScoped
    CircuitBreakerRegistry circuitBreakerRegistry() {
        final CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(failureRateThreshold)
            .waitDurationInOpenState(Duration.ofMillis(waitDurationOpenMs))
            .permittedNumberOfCallsInHalfOpenState(permittedCallsHalfOpen)
            .build();
        return CircuitBreakerRegistry.of(config);
    }
}

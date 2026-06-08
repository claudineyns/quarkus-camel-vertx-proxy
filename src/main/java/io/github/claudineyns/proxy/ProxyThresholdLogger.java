package io.github.claudineyns.proxy;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ProxyThresholdLogger {

    private static final org.jboss.logging.Logger log =
        org.jboss.logging.Logger.getLogger("proxy.thresholds");

    @ConfigProperty(name = "proxy.active-requests.limit", defaultValue = "-1")
    int globalLimit;

    @ConfigProperty(name = "proxy.active-requests.per-host-limit", defaultValue = "-1")
    int perHostLimit;

    @ConfigProperty(name = "proxy.http.client.pool.max-connections-per-host", defaultValue = "10")
    int poolMaxPerHost;

    void onStart(@Observes final StartupEvent ev) {
        log.debugf("proxy thresholds — active-requests.limit=%d  active-requests.per-host-limit=%d  http.client.pool.max-connections-per-host=%d",
            globalLimit, perHostLimit, poolMaxPerHost);
    }
}

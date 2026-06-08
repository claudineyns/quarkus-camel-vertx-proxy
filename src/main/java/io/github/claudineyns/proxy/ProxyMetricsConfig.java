package io.github.claudineyns.proxy;

import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class ProxyMetricsConfig {

    // Collapse per-path uri tags on http.server.requests into a single constant to prevent
    // unbounded cardinality: the proxy accepts arbitrary paths so each unique path would
    // otherwise create a new metric series.
    @Produces
    @Singleton
    MeterFilter httpServerUriFilter() {
        return MeterFilter.replaceTagValues("uri", v -> "proxy-route");
    }
}

package io.github.claudineyns.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.component.vertx.http.VertxHttpComponent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class VertxHttpComponentProducer {

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "proxy.http.client.pool.max-connections-per-host", defaultValue = "10")
    int maxConnectionsPerHost;

    @ConfigProperty(name = "proxy.http.client.pool.http2-max-connections-per-host", defaultValue = "5")
    int http2MaxConnectionsPerHost;

    @ConfigProperty(name = "proxy.http.client.pool.max-wait-queue-size", defaultValue = "-1")
    int maxWaitQueueSize;

    @ConfigProperty(name = "proxy.http.client.connect-timeout-ms", defaultValue = "5000")
    int connectTimeoutMs;

    @ConfigProperty(name = "proxy.http.client.response-timeout-ms", defaultValue = "5000")
    int responseTimeoutMs;

    @ConfigProperty(name = "proxy.http.client.idle-timeout-s", defaultValue = "30")
    int idleTimeoutS;

    @Produces
    @Named("vertx-http")
    @ApplicationScoped
    VertxHttpComponent vertxHttpComponent() {
        final WebClientOptions options = new WebClientOptions()
            .setFollowRedirects(false)
            .setTrustAll(true)
            .setVerifyHost(true)
            .setMaxPoolSize(maxConnectionsPerHost)
            .setHttp2MaxPoolSize(http2MaxConnectionsPerHost)
            .setMaxWaitQueueSize(maxWaitQueueSize)
            .setConnectTimeout(connectTimeoutMs)
            .setIdleTimeout(idleTimeoutS)
            .setIdleTimeoutUnit(TimeUnit.SECONDS)
            // response timeout approximated via read-idle timeout
            .setReadIdleTimeout(responseTimeoutMs / 1000 == 0 ? 1 : responseTimeoutMs / 1000)
            // setUseAlpn(true) activates ALPN at TLS level; setProtocolVersion prefers HTTP/2.
            // setAlpnVersions alone is insufficient — it lists protocols but does not enable ALPN.
            .setProtocolVersion(HttpVersion.HTTP_2)
            .setUseAlpn(true);

        final VertxHttpComponent component = new VertxHttpComponent();
        component.setVertx(vertx);
        component.setWebClientOptions(options);
        component.setVertxHttpBinding(new ProxyVertxHttpBinding());
        return component;
    }
}

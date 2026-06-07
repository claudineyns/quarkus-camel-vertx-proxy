package io.github.claudineyns.proxy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ProxyRouteBuilder extends RouteBuilder {

    static final String PROP_TARGET_BASE  = "proxyTargetBase";
    static final String PROP_CB           = "proxyCb";
    static final String PROP_CB_START_NS  = "proxyCbStartNs";
    static final String LOG_NAME          = "proxy.route";

    // RFC 7230 §6.1 hop-by-hop headers + legacy proxy headers (lowercase for case-insensitive matching)
    static final Set<String> HOP_BY_HOP = Set.of(
        "connection", "keep-alive", "transfer-encoding", "te",
        "trailers", "upgrade", "proxy-authorization", "proxy-authenticate",
        "proxy-connection"  // non-standard legacy header; must not be forwarded to the backend
    );

    @Inject
    CircuitBreakerRegistry cbRegistry;

    @Inject
    ConnectionPressureMonitor pressureMonitor;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @ConfigProperty(name = "quarkus.http.ssl-port", defaultValue = "8443")
    int httpsPort;

    @Override
    public void configure() {

        onException(Exception.class)
            .process(this::handleConnectivityException)
            .handled(true);

        from("platform-http:/?matchOnUriPrefix=true")
            .routeId("proxy-forward")
            .process(this::preProcess)
            .choice()
                .when(exchangeProperty(PROP_TARGET_BASE).isNotNull())
                    .log(LoggingLevel.DEBUG, LOG_NAME,
                        "→ [${header.CamelHttpMethod} ${exchangeProperty.proxyTargetBase}${header.CamelHttpPath}] headers=${headers}")
                    .toD("vertx-http:${exchangeProperty." + PROP_TARGET_BASE + "}?throwExceptionOnFailure=false")
                    .log(LoggingLevel.DEBUG, LOG_NAME,
                        "← backend [${header.CamelHttpResponseCode}] headers=${headers}")
                    .process(this::postProcess)
                    .log(LoggingLevel.DEBUG, LOG_NAME,
                        "← client  [${header.CamelHttpResponseCode}] headers=${headers}")
            .end();
    }

    // --- Pre-processing: guards + header preparation ---

    private void preProcess(final Exchange exchange) {
        final Message msg = exchange.getMessage();
        final String path = msg.getHeader(Exchange.HTTP_PATH, "/", String.class);

        // 1. WebSocket upgrade check
        final String upgradeHeader = msg.getHeader("upgrade",
            msg.getHeader("Upgrade", String.class), String.class);
        if ("websocket".equalsIgnoreCase(upgradeHeader)) {
            buildProblem(msg, 501, "/problems/not-implemented", "Not Implemented",
                "WebSocket not supported", path);
            return;
        }

        // 2. Resolve backend base URL
        final String backendBase = resolveBackendBase(exchange);
        if (backendBase == null) {
            buildProblem(msg, 502, "/problems/bad-gateway", "Bad Gateway",
                "Unable to determine backend target", path);
            return;
        }

        // 3. Self-reference guard
        if (isSelfReference(backendBase)) {
            buildProblem(msg, 502, "/problems/bad-gateway", "Bad Gateway",
                "Loop detected: request targets the proxy itself", path);
            return;
        }

        // 4. High-water-mark check
        if (pressureMonitor.isUnderPressure()) {
            buildProblem(msg, 503, "/problems/service-unavailable", "Service Unavailable",
                "Connection queue threshold exceeded", path);
            return;
        }

        // 5. Circuit breaker check
        final String hostPort = extractHostPort(backendBase);
        final CircuitBreaker cb = cbRegistry.circuitBreaker(hostPort);
        if (!cb.tryAcquirePermission()) {
            buildProblem(msg, 502, "/problems/bad-gateway", "Bad Gateway",
                "Circuit open: " + hostPort, path);
            return;
        }
        exchange.setProperty(PROP_CB, cb);
        exchange.setProperty(PROP_CB_START_NS, System.nanoTime());

        // 6. Inject X-Forwarded-* headers (before Host recalculation)
        injectForwardedHeaders(exchange, msg);

        // 7. Recalculate Host header for outbound request
        final URI backendUri = URI.create(backendBase);
        final int port = backendUri.getPort();
        msg.setHeader("Host",
            port > 0 ? backendUri.getHost() + ":" + port : backendUri.getHost());

        // 8. Remove hop-by-hop headers + primary routing header
        removeHopByHopHeaders(msg);
        msg.removeHeader("x-proxy-backend-base-url");

        // Exchange.HTTP_PATH and Exchange.HTTP_RAW_QUERY carry path and query for the outbound request.
        // HTTP_URI and HTTP_URL (set by consumer, pointing to the proxy itself) must be cleared so
        // the vertx-http producer uses the toD endpoint URI as base instead of the consumer's URL.
        // "*" is the request-line pseudo-header added by newer Camel versions — must not be forwarded.
        msg.removeHeader(Exchange.HTTP_URI);
        msg.removeHeader(Exchange.HTTP_URL);
        msg.removeHeader("*");

        // 9. Store target for toD
        exchange.setProperty(PROP_TARGET_BASE,
            backendBase.endsWith("/") ? backendBase.substring(0, backendBase.length() - 1) : backendBase);
    }

    // --- Post-processing: CB success + response rewrite ---

    private void postProcess(final Exchange exchange) {
        final Message msg = exchange.getMessage();

        final CircuitBreaker cb = exchange.getProperty(PROP_CB, CircuitBreaker.class);
        if (cb != null) {
            cb.onSuccess(elapsed(exchange), TimeUnit.NANOSECONDS);
        }

        final Integer status = msg.getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        if (status != null && (isRedirect(status) || status == 429)) {
            buildProblem(msg, 502, "/problems/bad-gateway", "Bad Gateway",
                "Unexpected backend response: " + status,
                msg.getHeader(Exchange.HTTP_PATH, "/", String.class));
        }

        // Remove Camel-internal headers from the response, keeping only the status code.
        msg.removeHeaders("Camel*", Exchange.HTTP_RESPONSE_CODE);
    }

    // --- Exception handler: connectivity failure → CB error + 502 ---

    private void handleConnectivityException(final Exchange exchange) {
        final CircuitBreaker cb = exchange.getProperty(PROP_CB, CircuitBreaker.class);
        if (cb != null) {
            final Exception ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
            cb.onError(elapsed(exchange), TimeUnit.NANOSECONDS,
                ex != null ? ex : new RuntimeException("unknown connectivity failure"));
        }

        final String hostPort = exchange.getProperty(PROP_TARGET_BASE) != null
            ? extractHostPort(exchange.getProperty(PROP_TARGET_BASE, String.class)) : "unknown";
        final Exception ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        final String detail = "Connection failure: " + hostPort
            + (ex != null ? " — " + rootCause(ex) : "");

        buildProblem(exchange.getMessage(), 502, "/problems/bad-gateway", "Bad Gateway", detail,
            exchange.getMessage().getHeader(Exchange.HTTP_PATH, "/", String.class));
    }

    // --- URL resolution ---

    private String resolveBackendBase(final Exchange exchange) {
        // Primary: explicit header
        final String primary = exchange.getMessage().getHeader("x-proxy-backend-base-url", String.class);
        if (primary != null && !primary.isBlank()) {
            return primary.strip();
        }

        // Intermediate: absolute URI from request-line (forward-proxy mode).
        // Exchange.HTTP_URL is avoided here: Vert.x constructs it as serverOrigin + rawUri,
        // corrupting the value when the incoming scheme differs from the request-line scheme.
        // routingContext.request().uri() returns the raw request-line URI without modification.
        final Object rc = exchange.getProperty("CamelVertxPlatformHttpRoutingContext");
        if (rc instanceof RoutingContext routingContext) {
            final String rawUri = routingContext.request().uri();
            if (rawUri != null && (rawUri.startsWith("http://") || rawUri.startsWith("https://"))) {
                try {
                    final URI parsed = URI.create(rawUri);
                    if (parsed.getScheme() != null && parsed.getHost() != null) {
                        final int port = parsed.getPort();
                        return parsed.getScheme() + "://" + parsed.getHost()
                            + (port > 0 ? ":" + port : "");
                    }
                } catch (final IllegalArgumentException ignored) {
                }
            }
        }

        // Fallback: Host header + scheme mirrored from the incoming connection.
        final String hostHeader = exchange.getMessage().getHeader("Host",
            exchange.getMessage().getHeader("host", String.class), String.class);
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        return incomingScheme(exchange) + "://" + hostHeader.strip();
    }

    private String incomingScheme(final Exchange exchange) {
        final Object rc = exchange.getProperty("CamelVertxPlatformHttpRoutingContext");
        if (rc instanceof RoutingContext routingContext) {
            return routingContext.request().scheme();
        }
        final String url = exchange.getMessage().getHeader(Exchange.HTTP_URL, String.class);
        if (url != null && url.startsWith("https://")) {
            return "https";
        }
        return "http";
    }

    private boolean isSelfReference(final String backendBase) {
        try {
            final URI uri = URI.create(backendBase);
            final int port = uri.getPort() < 0 ? defaultPort(uri.getScheme()) : uri.getPort();
            if (port != httpPort && port != httpsPort) {
                return false;
            }
            final InetAddress addr = InetAddress.getByName(uri.getHost());
            return addr.isLoopbackAddress() || addr.isAnyLocalAddress();
        } catch (final Exception ignored) {
            return false;
        }
    }

    private int defaultPort(final String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    // --- Header helpers ---

    private void injectForwardedHeaders(final Exchange exchange, final Message msg) {
        final Object rc = exchange.getProperty("CamelVertxPlatformHttpRoutingContext");
        final String originalHost = msg.getHeader("Host",
            msg.getHeader("host", String.class), String.class);

        if (rc instanceof RoutingContext routingContext) {
            final String clientIp = routingContext.request().remoteAddress().hostAddress();
            final String existing = msg.getHeader("X-Forwarded-For", String.class);
            msg.setHeader("X-Forwarded-For", existing != null ? existing + ", " + clientIp : clientIp);
            msg.setHeader("X-Forwarded-Proto", routingContext.request().scheme());
            msg.setHeader("X-Forwarded-Port", routingContext.request().localAddress().port());
        } else {
            msg.setHeader("X-Forwarded-Proto", incomingScheme(exchange));
        }
        if (originalHost != null) {
            msg.setHeader("X-Forwarded-Host", originalHost);
        }
    }

    private void removeHopByHopHeaders(final Message msg) {
        // Honor tokens listed in the Connection header before removing it
        final String connection = msg.getHeader("Connection",
            msg.getHeader("connection", String.class), String.class);
        if (connection != null) {
            for (final String token : connection.split(",")) {
                msg.removeHeader(token.strip());
            }
        }
        msg.getHeaders().keySet().removeIf(k -> HOP_BY_HOP.contains(k.toLowerCase()));
    }

    // --- RFC 9457 problem response ---

    private void buildProblem(final Message msg, final int status, final String type,
                               final String title, final String detail, final String instance) {
        msg.setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        msg.setHeader(Exchange.CONTENT_TYPE, "application/problem+json");
        msg.setBody(String.format(
            "{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"}",
            type, title, status,
            detail.replace("\\", "\\\\").replace("\"", "\\\""),
            instance.replace("\\", "\\\\").replace("\"", "\\\"")
        ));
        // Clear target so the choice() branch skips toD
        msg.getExchange().removeProperty(PROP_TARGET_BASE);
    }

    // --- Utilities ---

    private String extractHostPort(final String base) {
        try {
            final URI uri = URI.create(base);
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (final Exception ignored) {
            return base;
        }
    }

    private boolean isRedirect(final int status) {
        return status >= 300 && status < 400;
    }

    private long elapsed(final Exchange exchange) {
        final Long start = exchange.getProperty(PROP_CB_START_NS, Long.class);
        return start != null ? System.nanoTime() - start : 0L;
    }

    private String rootCause(final Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        final String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }
}

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

public class ProxyTest {

    private static final String PROXY_HOST = "proxy-app";
    private static final int    PROXY_PORT = 8443;

    public static void main(final String[] args) throws Exception {
        final String backendUrl = resolveBackendUrl();
        final URI    uri        = URI.create(backendUrl);

        final String host    = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        final String rawPath = uri.getRawPath();
        final String path    = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        final String query   = uri.getRawQuery();
        final String target  = path + (query != null ? "?" + query : "");

        // Absolute URI in request-line — forward-proxy mode (no x-proxy-backend-base-url header).
        // The proxy extracts scheme + host from Exchange.HTTP_URL populated by the platform-http consumer.
        final String requestLine = "GET " + uri.getScheme() + "://" + host + target + " HTTP/1.1";

        System.out.println("[proxy-test] backend : " + backendUrl);
        System.out.println("[proxy-test] proxy   : " + PROXY_HOST + ":" + PROXY_PORT);
        System.out.println("[proxy-test] sending : " + requestLine);

        final SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, new TrustManager[]{ new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(final X509Certificate[] c, final String a) {}
            public void checkServerTrusted(final X509Certificate[] c, final String a) {}
        }}, new SecureRandom());

        final SSLSocketFactory factory = sslCtx.getSocketFactory();
        try (final SSLSocket socket = (SSLSocket) factory.createSocket(PROXY_HOST, PROXY_PORT)) {
            socket.startHandshake();

            final String request = requestLine + "\r\n"
                + "Host: "       + host + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

            final OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            System.out.println("[proxy-test] request sent — aguardando 5s antes de fechar conexão");
            Thread.sleep(5_000);
        }
    }

    private static String resolveBackendUrl() {
        final String fromEnv = System.getenv("BACKEND_URL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        final String fromProp = System.getProperty("backend.url");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.strip();
        }
        System.err.println("[proxy-test] erro: defina BACKEND_URL (env) ou -Dbackend.url (system property)");
        System.exit(1);
        return null;
    }
}

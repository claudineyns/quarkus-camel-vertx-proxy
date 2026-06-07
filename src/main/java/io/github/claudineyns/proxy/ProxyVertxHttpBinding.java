package io.github.claudineyns.proxy;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import org.apache.camel.Exchange;
import org.apache.camel.component.vertx.http.DefaultVertxHttpBinding;
import org.apache.camel.spi.HeaderFilterStrategy;

public class ProxyVertxHttpBinding extends DefaultVertxHttpBinding {

    @Override
    public void populateResponseHeaders(final Exchange exchange,
                                        final HttpResponse<Buffer> response,
                                        final HeaderFilterStrategy headerFilterStrategy) {
        // Clear all request headers before populating with backend response headers,
        // preventing client request headers from leaking into the response pipeline.
        exchange.getMessage().removeHeaders("*");
        super.populateResponseHeaders(exchange, response, headerFilterStrategy);
    }
}

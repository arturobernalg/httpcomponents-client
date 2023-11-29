package org.apache.hc.client5.http;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;

public class ClientInterceptors {

    public static void main(final String[] args) throws Exception {
        // Create a connection manager with custom configurations
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(100);
        cm.setDefaultMaxPerRoute(20);
        SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout(Timeout.of(6000, TimeUnit.SECONDS)) // 1 minute
                .build();
        cm.setDefaultSocketConfig(socketConfig);

        // Create an HttpClient with the connection manager
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.of(60000, TimeUnit.SECONDS)) // 1 minute
                        .setProxy(new HttpHost("127.0.0.1", 8888)) // Set your own proxy
                        .build())
                .addRequestInterceptorFirst((HttpRequest request, EntityDetails entity, HttpContext context) -> {
                    String method = request.getMethod();
                    System.out.println("method: " + method);
                    if (method.equals("CONNECT")) {
                        request.addHeader("Custom-Header", "value");
                    }
                })
                .build();

        try {
            // Create a request
            HttpGet request = new HttpGet("https://www.google.com/");
            System.out.println("Executing request " + request);

            // Execute the request and get a response
            try (CloseableHttpResponse response = client.execute(request)) {
                System.out.println("Response: " + response);
                // Process the response body if needed
                EntityUtils.consume(response.getEntity());
            }
        } finally {
            // Close the client
            client.close();
        }
    }
}

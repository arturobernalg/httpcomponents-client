package org.apache.hc.client5.http;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.ssl.TLS;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509KeyManager;
import java.io.File;

public class MTLSHttpClient {


    public static void main(String[] args) throws Exception {
        // Load client key and certificate
        SSLContext sslContext = SSLContextBuilder.create()
                //  .setProtocol("TLSv1.3") // Use TLSv1.3 explicitly

                .loadKeyMaterial(
                        new File("/home/arturo/project/apache/httpcomponents-core/ecdsa_keystore.p12"),
                        "changeit".toCharArray(),
                        "changeit".toCharArray()
                )
                .loadTrustMaterial(
                        new File("/home/arturo/project/apache/httpcomponents-core/ca_truststore.jks"),
                        "changeit".toCharArray()
                )
                .build();

        final TlsSocketStrategy tlsStrategy = new DefaultClientTlsStrategy(sslContext,
                NoopHostnameVerifier.INSTANCE);

        // Allow TLSv1.3 protocol only
        final HttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tlsStrategy)
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setHandshakeTimeout(Timeout.ofSeconds(30))
                        .setSupportedProtocols(TLS.V_1_3)
                        .build())
                .build();

        // Create an HTTP client with mutual TLS
        try (CloseableHttpClient httpclient = HttpClients.custom()
                .setConnectionManager(cm)
                .build()) {

            // Make a request to the server
            HttpGet httpget = new HttpGet("https://ecdsa-client:8443/");
            httpget.setHeader("Host", "ecdsa-client");
            final HttpClientContext clientContext = HttpClientContext.create();
            httpclient.execute(httpget, clientContext, response -> {
                System.out.println("----------------------------------------");
                System.out.println(httpget + "->" + new StatusLine(response));
                EntityUtils.consume(response.getEntity());
                final SSLSession sslSession = clientContext.getSSLSession();
                if (sslSession != null) {
                    System.out.println("SSL protocol " + sslSession.getProtocol());
                    System.out.println("SSL cipher suite " + sslSession.getCipherSuite());
                }
                return null;
            });
        }
    }
}
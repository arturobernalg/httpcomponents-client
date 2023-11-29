package org.apache.hc.client5.http;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityConsumer;
import org.apache.hc.core5.http.nio.support.BasicResponseConsumer;
import org.apache.hc.core5.http.ssl.TLS;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

public class App
{
    public static void main(final String[] args) throws Exception {

        final HttpAsyncClientBuilder clientBuilder = HttpAsyncClients.custom()
                .addRequestInterceptorLast((request, entity, context) -> {
                    String method = request.getMethod();
                    System.out.println("method: " + method);
                    if (method.equals("CONNECT")) {
                        request.addHeader("Custom-Header", "value");
                    }
                })
                .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                        .setTlsStrategy(ClientTlsStrategyBuilder.create()
                                .setTlsVersions(TLS.V_1_3)
                                .build())
                        .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.LAX)
                        .setConnPoolPolicy(PoolReusePolicy.LIFO)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setSocketTimeout(Timeout.ofMinutes(1))
                                .setConnectTimeout(Timeout.ofMinutes(1))
                                .setTimeToLive(Timeout.ofMinutes(1))
                                .build())
                        .setDefaultTlsConfig(TlsConfig.custom()
                                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                                .setHandshakeTimeout(Timeout.ofMinutes(1))
                                .build())
                        .build())
                .setIOReactorConfig(IOReactorConfig.custom()
                        .setSoTimeout(Timeout.ofMinutes(1))
                        .setIoThreadCount(1)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setCookieSpec(StandardCookieSpec.STRICT)
                        .build())
                .disableCookieManagement()
                .setProxy(new HttpHost("127.0.0.1", 8888)); //Set your own proxy

        final CloseableHttpAsyncClient client = clientBuilder.build();
        client.start();

        CountDownLatch latch = new CountDownLatch(1);

        final String requestUri = "https://www.google.com/";
        final SimpleHttpRequest request = SimpleRequestBuilder.get(requestUri).build();
        final SimpleRequestProducer producer = SimpleRequestProducer.create(request);
        final AsyncResponseConsumer<Message<HttpResponse, byte[]>> consumer = new BasicResponseConsumer<>(new BasicAsyncEntityConsumer());

        System.out.println("Executing request " + request);
        FutureCallback<Message<HttpResponse, byte[]>> callback = new FutureCallback<Message<HttpResponse, byte[]>>() {

            @Override
            public void completed(Message<HttpResponse, byte[]> response) {
                latch.countDown();
                System.out.println(response.getHead().getVersion() + " " + response.getHead().getCode());
            }

            @Override
            public void failed(Exception ex) {
                latch.countDown();
                System.out.println("Error executing HTTP request: " + ex.getMessage());
            }

            @Override
            public void cancelled() {
                latch.countDown();
                System.out.println("HTTP request execution cancelled");
            }

        };

        Future<Message<org.apache.hc.core5.http.HttpResponse, byte[]>> future = client.execute(producer, consumer, callback);
        latch.await();
        future.get();

        System.out.println("Shutting down");
        client.close(CloseMode.GRACEFUL);
    }

}
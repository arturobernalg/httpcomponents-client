package org.apache.hc.client5.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.message.StatusLine;

public class testClient {

    static HttpClient client;

    public static void main(String[] args) throws IOException, URISyntaxException {

        client = HttpClientFactory.createHttpClient();

        readFile("http://localhost:8000/assert/1");
        readFile("http://localhost:8000/assert/2");
        readFile("http://localhost:8000/assert/1");
        readFile("http://localhost:8000/assert/2");
        HttpClientFactory.dumpCache();
    }

    public static void readFile(final String url) throws IOException, URISyntaxException {
        final ClassicHttpRequest request = ClassicRequestBuilder.get()
                .setUri(new URI(url))
                .build();
        System.out.println("Executing request " + request);
        client.execute(request, response -> {
            System.out.println("----------------------------------------");
            System.out.println(request + "->" + new StatusLine(response));

            // Print out the headers
            Header[] headers = response.getHeaders();
            for (Header header : headers) {
                System.out.println(header.getName() + ": " + header.getValue());
            }

            final HttpEntity entity = response.getEntity();
            return entity != null ? EntityUtils.toByteArray(entity) : null;
        });
    }
}
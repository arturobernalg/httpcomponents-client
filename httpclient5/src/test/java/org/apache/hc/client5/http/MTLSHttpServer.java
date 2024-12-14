package org.apache.hc.client5.http;

import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.TimeValue;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

class MTLSHttpServer {

    public static void main(String[] args) throws Exception {
        // Paths and passwords for the keystore and truststore
        File keyStoreFile = new File("/home/arturo/project/apache/httpcomponents-core/ecdsa_keystore.p12");
        File trustStoreFile = new File("/home/arturo/project/apache/httpcomponents-core/ca_truststore.jks");
        char[] keyStorePassword = "changeit".toCharArray();
        char[] trustStorePassword = "changeit".toCharArray();

        // Load the keystore for server's private key and certificate
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream keyStoreStream = new FileInputStream(keyStoreFile)) {
            keyStore.load(keyStoreStream, keyStorePassword);
        }

        // Load the truststore for client certificate validation
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (FileInputStream trustStoreStream = new FileInputStream(trustStoreFile)) {
            trustStore.load(trustStoreStream, trustStorePassword);
        }

        // Create KeyManager for server's private key and certificate
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keyStorePassword);
        KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();

        // Create TrustManager for validating client certificates
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

        // Use SecureRandom for additional security
        SecureRandom secureRandom = new SecureRandom();

        // Initialize SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers, trustManagers, secureRandom);

        // Create and start an HTTPS server with mutual TLS
        HttpServer server = ServerBootstrap.bootstrap()
                .setListenerPort(8443)
                .setSslContext(sslContext)
                .setSslSetupHandler(sslParameters -> {
                    sslParameters.setNeedClientAuth(true); // Require client certificates
                })
                .setCanonicalHostName("ecdsa-client")
                .register("/", (request, response, context) -> {
                    response.setCode(200);
                    response.setEntity(new StringEntity("Hello, Client with Certificate!"));
                })
                .create();

        // Start the server
        server.start();
        System.out.println("Server started on https://localhost:8443");
        server.awaitTermination(TimeValue.MAX_VALUE);
    }
}
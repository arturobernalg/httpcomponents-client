package org.apache.hc.client5.http;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509KeyManager;
import java.io.FileInputStream;
import java.security.KeyStore;

public class KeyManagerUtil {

    public static X509KeyManager getDefaultKeyManager(String keystorePath, String keystorePassword) throws Exception {
        // Load the keystore
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            keyStore.load(fis, keystorePassword.toCharArray());
        }

        // Initialize KeyManagerFactory
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keystorePassword.toCharArray());

        // Extract the X509KeyManager
        for (KeyManager keyManager : keyManagerFactory.getKeyManagers()) {
            if (keyManager instanceof X509KeyManager) {
                return (X509KeyManager) keyManager;
            }
        }

        throw new IllegalStateException("No X509KeyManager found");
    }
}
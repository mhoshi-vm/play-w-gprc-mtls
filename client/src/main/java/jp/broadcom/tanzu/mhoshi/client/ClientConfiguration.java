package jp.broadcom.tanzu.mhoshi.client;

import io.grpc.ChannelCredentials;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettySslContextChannelCredentials;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.netty.handler.ssl.SslContext;
import jp.broadcom.tanzu.mhoshi.server.proto.SimpleGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

import javax.net.ssl.SSLException;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.List;

@Configuration
class ClientConfiguration {

    Logger log = LoggerFactory.getLogger(ClientConfiguration.class);

    @Bean
    SimpleGrpc.SimpleBlockingStub stub(GrpcChannelFactory channels) {
        return SimpleGrpc.newBlockingStub(channels.createChannel("local"));
    }

    // 1. Create the dynamic KeyManager as a Spring Bean
    @Bean
    public AdvancedTlsX509KeyManager grpcKeyManager(SslBundles sslBundles,
                                                    @Value("${spring.grpc.client.channels.local.ssl.bundle}") String bundleName,
                                                    @Value("${spring.ssl.bundle.pem.${spring.grpc.client.channels.local.ssl.bundle}.key.alias}") String aliasName
    ) throws Exception {
        AdvancedTlsX509KeyManager keyManager = new AdvancedTlsX509KeyManager();

        // Load the initial certificates on startup
        SslBundle initialBundle = sslBundles.getBundle(bundleName);
        updateKeyManager(keyManager, initialBundle, aliasName);

        // Register the official Spring Boot callback for when the files change on disk
        sslBundles.addBundleUpdateHandler(bundleName, updatedBundle -> {
            try {
                log.info("SSL Bundle updated, updating key manager");
                updateKeyManager(keyManager, updatedBundle, aliasName);
            } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });

        return keyManager;
    }

    // 2. Pass the dynamic KeyManager to the gRPC Channel Configurer
    @Bean
    ChannelCredentials grpcChannelCredentials(AdvancedTlsX509KeyManager keyManager) throws SSLException {
        SslContext sslContext = GrpcSslContexts.forClient()
                .keyManager(keyManager)
                .build();

        // Wrap the Netty SslContext into the new transport-agnostic Credentials API
        return NettySslContextChannelCredentials.create(sslContext);
    }


    private void updateKeyManager(AdvancedTlsX509KeyManager keyManager, SslBundle bundle,
                                  String aliasName) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException {

        KeyStore keyStore = bundle.getStores().getKeyStore();
        String password = bundle.getStores().getKeyStorePassword();
        char[] passChars = (password != null) ? password.toCharArray() : new char[0];

        X509Certificate cert = (X509Certificate) keyStore.getCertificate(aliasName);
        X509Certificate[] x509Certs = List.of(cert).toArray(new X509Certificate[0]);
        PrivateKey key = (PrivateKey) keyStore.getKey(aliasName, passChars);
        keyManager.updateIdentityCredentials(x509Certs, key);

    }
}

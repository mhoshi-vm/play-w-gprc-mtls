package jp.broadcom.tanzu.mhoshi.client.cf;

import io.grpc.TlsChannelCredentials;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettySslContextChannelCredentials;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ChannelCredentialsProvider;

import javax.net.ssl.SSLException;
import java.security.*;
import java.security.cert.X509Certificate;

@Configuration
@ConditionalOnProperty(name = "spring.grpc.client.channels.local.ssl.bundle")
class CfClientCertificateConfig {

    Logger log = LoggerFactory.getLogger(CfClientCertificateConfig.class);

    @Bean
    public AdvancedTlsX509KeyManager grpcKeyManager(SslBundles sslBundles,
                                                    @Value("${spring.grpc.client.channels.local.ssl.bundle}") String bundleName,
                                                    @Value("${spring.ssl.bundle.pem.${spring.grpc.client.channels.local.ssl.bundle}.key.alias}") String aliasName
    ) throws Exception {
        AdvancedTlsX509KeyManager keyManager = new AdvancedTlsX509KeyManager();

        SslBundle initialBundle = sslBundles.getBundle(bundleName);
        updateKeyManager(keyManager, initialBundle, aliasName);

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

    /**
     * Provides a {@link ChannelCredentialsProvider} that plugs the dynamic
     * {@link AdvancedTlsX509KeyManager} into the Netty SSL context.
     *
     * <p>Because Spring gRPC's auto-configured {@code NamedChannelCredentialsProvider} is
     * annotated with {@code @ConditionalOnMissingBean(ChannelCredentialsProvider.class)},
     * declaring this bean causes the auto-configured provider to be skipped — so every
     * gRPC channel will use our key manager with transparent cert hot-reload.
     */
    @Bean
    ChannelCredentialsProvider grpcChannelCredentialsProvider(SslBundles sslBundles,
                                                              @Value("${spring.grpc.client.channels.local.ssl.bundle}") String bundleName,
                                                              AdvancedTlsX509KeyManager keyManager) {

        return channelName -> {
            SslBundle bundle = sslBundles.getBundle(bundleName);
            return TlsChannelCredentials.newBuilder()
                    .keyManager(keyManager)
                    .trustManager(bundle.getManagers().getTrustManagerFactory().getTrustManagers())
                    .build();
        };
//        return channelName -> {
//            try {
//                SslBundle bundle = sslBundles.getBundle(bundleName);
//                SslContext ssl = GrpcSslContexts
//                        .configure(SslContextBuilder.forClient(), SslProvider.JDK)
//                        .keyManager(keyManager)
//                        .trustManager(bundle.getManagers().getTrustManagerFactory())
//                        .build();
//                return NettySslContextChannelCredentials.create(ssl);
//            } catch (SSLException e) {
//                throw new IllegalStateException("Cannot build gRPC channel credentials", e);
//            }
//        };
    }

    private void updateKeyManager(AdvancedTlsX509KeyManager keyManager, SslBundle bundle,
                                  String aliasName) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException {

        KeyStore keyStore = bundle.getStores().getKeyStore();
        String password = bundle.getStores().getKeyStorePassword();
        char[] passChars = (password != null) ? password.toCharArray() : new char[0];

        // Fetch the full certificate chain instead of just the leaf certificate
        assert keyStore != null;
        java.security.cert.Certificate[] certChain = keyStore.getCertificateChain(aliasName);

        // Convert to X509Certificate array
        X509Certificate[] x509Certs = new X509Certificate[certChain.length];
        for (int i = 0; i < certChain.length; i++) {
            x509Certs[i] = (X509Certificate) certChain[i];
        }
        PrivateKey key = (PrivateKey) keyStore.getKey(aliasName, passChars);
        keyManager.updateIdentityCredentials(x509Certs, key);

    }
}

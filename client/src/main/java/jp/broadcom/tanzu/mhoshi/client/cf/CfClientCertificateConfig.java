package jp.broadcom.tanzu.mhoshi.client.cf;

import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettySslContextChannelCredentials;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ChannelCredentialsProvider;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Collections;

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
        updateKeyManager(keyManager, sslBundles.getBundle(bundleName), aliasName);
        return keyManager;
    }

    /**
     * Builds the Netty {@link SslContext} once, using JDK SSL so that
     * {@link AdvancedTlsX509KeyManager} is called on every new TLS handshake.
     * BoringSSL (the default) caches key material at context-build time and
     * would ignore the key manager's dynamic updates.
     */
    @Bean
    SslContext grpcClientSslContext(SslBundles sslBundles,
                                    @Value("${spring.grpc.client.channels.local.ssl.bundle}") String bundleName,
                                    AdvancedTlsX509KeyManager keyManager) throws SSLException {
        SslBundle bundle = sslBundles.getBundle(bundleName);
        return GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
                .keyManager(keyManager)
                .trustManager(bundle.getManagers().getTrustManagerFactory())
                .build();
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
    ChannelCredentialsProvider grpcChannelCredentialsProvider(SslContext grpcClientSslContext) {
        return channelName -> NettySslContextChannelCredentials.create(grpcClientSslContext);
    }

    /**
     * Registers the SSL-bundle update handler AFTER the application context is fully
     * started, so {@code localChannel} is available without a circular dependency.
     *
     * <p>On cert rotation the handler:
     * <ol>
     *   <li>Pushes the new key/cert into the shared {@link AdvancedTlsX509KeyManager}.</li>
     *   <li>Clears the JDK SSL client-session cache so the next TCP connection performs a
     *       full TLS handshake — without this, TLS 1.3 session-ticket resumption would
     *       cause the server to see the old client cert even on a fresh connection.</li>
     *   <li>Calls {@link ManagedChannel#enterIdle()} to tear down the existing HTTP/2
     *       connection; the next RPC will establish a new one.</li>
     * </ol>
     */
    @Bean
    ApplicationRunner certRotationListener(SslBundles sslBundles,
                                           @Value("${spring.grpc.client.channels.local.ssl.bundle}") String bundleName,
                                           @Value("${spring.ssl.bundle.pem.${spring.grpc.client.channels.local.ssl.bundle}.key.alias}") String aliasName,
                                           AdvancedTlsX509KeyManager keyManager,
                                           SslContext grpcClientSslContext,
                                           @Qualifier("localChannel") ManagedChannel localChannel) {
        return args -> sslBundles.addBundleUpdateHandler(bundleName, updatedBundle -> {
            try {
                log.info("SSL Bundle updated, updating key manager");
                updateKeyManager(keyManager, updatedBundle, aliasName);
                clearJdkSslSessions(grpcClientSslContext);
                localChannel.enterIdle();
            } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Invalidates every entry in the JDK SSL client-session cache so that the next
     * connection to the same host:port performs a full TLS handshake rather than resuming
     * the old session (which carries the previous client certificate).
     */
    private void clearJdkSslSessions(SslContext ssl) {
        if (!(ssl instanceof JdkSslContext)) {
            return;
        }
        SSLSessionContext sessionCtx = ((JdkSslContext) ssl).context().getClientSessionContext();
        for (byte[] id : Collections.list(sessionCtx.getIds())) {
            SSLSession session = sessionCtx.getSession(id);
            if (session != null) {
                session.invalidate();
            }
        }
    }

    private void updateKeyManager(AdvancedTlsX509KeyManager keyManager, SslBundle bundle,
                                  String aliasName) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException {

        KeyStore keyStore = bundle.getStores().getKeyStore();
        String password = bundle.getStores().getKeyStorePassword();
        char[] passChars = (password != null) ? password.toCharArray() : new char[0];

        assert keyStore != null;
        java.security.cert.Certificate[] certChain = keyStore.getCertificateChain(aliasName);

        X509Certificate[] x509Certs = new X509Certificate[certChain.length];
        for (int i = 0; i < certChain.length; i++) {
            x509Certs[i] = (X509Certificate) certChain[i];
        }
        PrivateKey key = (PrivateKey) keyStore.getKey(aliasName, passChars);
        keyManager.updateIdentityCredentials(x509Certs, key);
    }
}
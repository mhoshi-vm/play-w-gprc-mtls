package jp.broadcom.tanzu.mhoshi.client;

import io.grpc.*;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import jp.broadcom.tanzu.mhoshi.server.proto.HelloReply;
import jp.broadcom.tanzu.mhoshi.server.proto.HelloRequest;
import jp.broadcom.tanzu.mhoshi.server.proto.SimpleGrpc;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.client.ChannelCredentialsProvider;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that the client's certificate is hot-reloaded while the
 * application is running, and that the rotated certificate is actually presented to the
 * server on the next gRPC connection.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>A fresh ephemeral CA key pair is generated for the test.  All certs (initial client,
 *       server, rotated client) are signed by this CA, so the server's trust store and the
 *       issued certs are always consistent.</li>
 *   <li>An initial client cert and a server cert (with SAN=localhost) are written to a temp
 *       directory.  The Spring SSL bundle watches those files.</li>
 *   <li>A {@link ChannelCredentialsProvider} bean plugs the shared
 *       {@link AdvancedTlsX509KeyManager} into the Netty SSL context with JDK SSL, so every
 *       new TLS handshake calls {@code getCertificateChain} dynamically instead of caching at
 *       build time.</li>
 *   <li>Initial gRPC call → client cert serial N recorded by a server interceptor.</li>
 *   <li>New client cert (signed by the same ephemeral CA) is atomically written to the
 *       watched files, triggering Spring Boot's file-watcher.</li>
 *   <li>Spring Boot's file-watcher fires the {@code certRotationListener} bundle update handler
 *       first: it pushes the new key/cert into the key manager, invalidates all entries in the
 *       JDK SSL client-session cache (preventing TLS session-ticket resumption from replaying
 *       the old cert), and calls {@link ManagedChannel#enterIdle()} to close the existing
 *       HTTP/2 connection.  A test {@link CountDownLatch} registered as the next handler then
 *       fires to unblock the test thread.</li>
 *   <li>Second gRPC call → server records serial M ≠ N, proving hot-reload works end-to-end.
 *       </li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Disable the default "self-signed" profile so its properties don't interfere.
        properties = "spring.profiles.active=")
class CertHotReloadTest {

    // ── shared static state ───────────────────────────────────────────────────

    @TempDir
    static Path certDir;

    /**
     * Port the embedded gRPC server listens on.
     */
    static int grpcPort;

    /**
     * Stores the most-recently-observed client cert serial from the server side.
     */
    static final AtomicReference<BigInteger> lastClientSerial = new AtomicReference<>();

    /**
     * Embedded gRPC server.
     */
    static io.grpc.Server grpcServer;

    /**
     * Server-side SSL context (trusts our ephemeral test CA, requires client certs).
     */
    static SslContext serverSslContext;

    /**
     * Ephemeral CA key pair generated once per test class.  All certs in the test are
     * signed by this CA so that the server trust store and the client certs always match.
     */
    static KeyPair testCaKP;
    static X509Certificate testCaCert;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    // ── Spring context bootstrap ──────────────────────────────────────────────

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
        // 1. Generate an ephemeral CA for this test run.  The classpath ca.key does NOT
        //    correspond to ca.crt (they are unrelated key pairs), so we always generate
        //    a fresh CA rather than loading one from classpath resources.
        testCaKP = generateKeyPair();
        testCaCert = generateCaCert(testCaKP);

        // 2. Write the CA cert to certDir (Spring SSL bundle truststore watches this file).
        writeCert(testCaCert, certDir.resolve("ca.crt"));

        // 3. Generate an initial client cert/key, signed by the ephemeral CA.
        KeyPair initialClientKP = generateKeyPair();
        X509Certificate initialClientCert = issueCert(
                "CN=client-v1,O=test", initialClientKP.getPublic(),
                testCaKP.getPrivate(), testCaCert, BigInteger.ONE, /* addLocalhostSan= */ false);
        writeCert(initialClientCert, certDir.resolve("client.crt"));
        writeKey(initialClientKP.getPrivate(), certDir.resolve("client.key"));

        // 4. Generate a server cert with SAN=localhost, signed by the ephemeral CA.
        KeyPair serverKP = generateKeyPair();
        X509Certificate serverCert = issueCert(
                "CN=localhost,O=test-server", serverKP.getPublic(),
                testCaKP.getPrivate(), testCaCert, BigInteger.TWO, /* addLocalhostSan= */ true);

        // 5. Build a shared server TLS context (trusts our CA, requires client certs).
        serverSslContext = GrpcSslContexts.configure(
                SslContextBuilder.forServer(serverKP.getPrivate(), serverCert)
                        .trustManager(testCaCert)
                        .clientAuth(ClientAuth.REQUIRE)
        ).build();

        // 6. Start the embedded gRPC server on a random port.
        grpcServer = startServer();
        grpcPort = grpcServer.getPort();

        // 7. Point the Spring gRPC channel at the embedded server and configure the
        //    PEM-based SSL bundle to watch the temp-dir cert files.
        registry.add("spring.grpc.client.channels.local.address",
                () -> "static://localhost:" + grpcPort);
        registry.add("spring.grpc.client.channels.local.negotiation-type", () -> "TLS");
        registry.add("spring.grpc.client.channels.local.ssl.bundle", () -> "test-certs");

        registry.add("spring.ssl.bundle.pem.test-certs.key.alias", () -> "client");
        registry.add("spring.ssl.bundle.pem.test-certs.reload-on-update", () -> "true");
        registry.add("spring.ssl.bundle.pem.test-certs.truststore.certificate",
                () -> certDir.resolve("ca.crt").toAbsolutePath().toString());
        registry.add("spring.ssl.bundle.pem.test-certs.keystore.certificate",
                () -> certDir.resolve("client.crt").toAbsolutePath().toString());
        registry.add("spring.ssl.bundle.pem.test-certs.keystore.private-key",
                () -> certDir.resolve("client.key").toAbsolutePath().toString());

        // Shorten the file-watcher quiet period so the test reacts promptly.
        // A non-zero value avoids busy-spinning and prevents reload during a partial write.
        // NOTE: spring.ssl.bundle.watch.file.poll-interval is NOT a valid property.
        registry.add("spring.ssl.bundle.watch.file.quiet-period", () -> "200ms");
    }

    @AfterAll
    static void stopEmbeddedServer() throws InterruptedException {
        if (grpcServer != null) {
            grpcServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }


    // ── injected beans ────────────────────────────────────────────────────────

    @Autowired
    SimpleGrpc.SimpleBlockingStub stub;

    @Autowired
    AdvancedTlsX509KeyManager keyManager;

    @Autowired
    SslBundles sslBundles;

    // ── test ──────────────────────────────────────────────────────────────────

    @Test
    void clientCertIsHotReloadedAndUsedForGrpcCommunication() throws Exception {

        // ── Phase 1: baseline call with the original certificate ──────────────

        HelloReply reply1 = stub.sayHello(HelloRequest.newBuilder().setName("hello-v1").build());
        assertThat(reply1.getMessage()).contains("hello-v1");

        BigInteger serialBefore = lastClientSerial.get();
        assertThat(serialBefore)
                .as("Server must capture the client cert serial on the first call")
                .isNotNull();

        // ── Phase 2: prepare the rotated certificate ──────────────────────────
        //
        // Sign the new client cert with the same ephemeral CA that the server trusts.

        KeyPair newClientKP = generateKeyPair();
        X509Certificate newClientCert = issueCert(
                "CN=client-v2,O=test", newClientKP.getPublic(),
                testCaKP.getPrivate(), testCaCert,
                // Use current-time millis to guarantee a unique serial number.
                BigInteger.valueOf(System.currentTimeMillis()),
                /* addLocalhostSan= */ false);

        // ── Phase 3: register a latch BEFORE overwriting the cert files ───────
        //
        // sslBundles.addBundleUpdateHandler fires every time Spring Boot's file-watcher
        // detects a change and successfully reloads the bundle.  Registering before the
        // write guarantees we won't miss the notification.

        CountDownLatch rotationDetected = new CountDownLatch(1);
        sslBundles.addBundleUpdateHandler("test-certs",
                updatedBundle -> rotationDetected.countDown());

        // ── Phase 4: overwrite the watched files ──────────────────────────────
        //
        // Atomic write (write to .tmp, then rename) produces a reliable WatchService
        // ENTRY_CREATE/ENTRY_MODIFY event regardless of the OS implementation.

        writeCert(newClientCert, certDir.resolve("client.crt"));
        writeKey(newClientKP.getPrivate(), certDir.resolve("client.key"));

        // ── Phase 5: wait for Spring Boot to reload the bundle ────────────────
        //
        // The file-watcher (ssl-bundle-watcher thread) detects the change after
        // quiet-period=200ms of no further modifications, reloads the bundle, and
        // calls all registered update handlers — including our latch.

        assertThat(rotationDetected.await(30, TimeUnit.SECONDS))
                .as("SSL bundle 'test-certs' must reload within 30 s after cert files are overwritten")
                .isTrue();

        // Verify the key manager actually carries the new certificate.
        // AdvancedTlsX509KeyManager uses the alias "default" internally; any other alias
        // returns null regardless of whether updateIdentityCredentials has been called.
        X509Certificate[] updatedChain = keyManager.getCertificateChain("default");
        assertThat(updatedChain)
                .as("Key manager must have a certificate chain after rotation")
                .isNotNull()
                .hasSizeGreaterThan(0);

        BigInteger serialAfter = updatedChain[0].getSerialNumber();
        assertThat(serialAfter)
                .as("Key manager must carry the new certificate serial after rotation")
                .isNotEqualTo(serialBefore);

        // ── Phase 6: prove the new cert is used on a fresh gRPC connection ───
        HelloReply reply2 = stub.sayHello(HelloRequest.newBuilder().setName("hello-v2").build());
        assertThat(reply2.getMessage()).contains("hello-v2");

        assertThat(lastClientSerial.get())
                .as("Server must observe the rotated certificate on the fresh connection")
                .isEqualTo(serialAfter);
    }

    // ── server helpers ────────────────────────────────────────────────────────

    /**
     * Starts the embedded gRPC server.
     */
    static io.grpc.Server startServer() throws Exception {
        return NettyServerBuilder.forPort(0)
                .sslContext(serverSslContext)
                .addService(new TestSimpleService())
                .intercept(new ClientCertRecordingInterceptor())
                .build()
                .start();
    }

    // ── embedded gRPC service ─────────────────────────────────────────────────

    static class TestSimpleService extends SimpleGrpc.SimpleImplBase {
        @Override
        public void sayHello(HelloRequest req, StreamObserver<HelloReply> out) {
            out.onNext(HelloReply.newBuilder()
                    .setMessage("Hello => " + req.getName())
                    .build());
            out.onCompleted();
        }
    }

    // ── server interceptor: captures client cert serial per call ──────────────

    static class ClientCertRecordingInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            try {
                SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
                if (session != null) {
                    X509Certificate cert =
                            (X509Certificate) session.getPeerCertificates()[0];
                    lastClientSerial.set(cert.getSerialNumber());
                }
            } catch (Exception ignored) {
                // No client cert or SSL session — let the call fail normally.
            }
            return next.startCall(call, headers);
        }
    }

    // ── certificate utilities ─────────────────────────────────────────────────

    /**
     * Generates a self-signed CA certificate from the given key pair.
     * The CA cert has {@code BasicConstraints: CA=TRUE} and {@code KeyUsage: keyCertSign}.
     */
    static X509Certificate generateCaCert(KeyPair caKP) throws Exception {
        X500Name subject = new X500Name("CN=Test CA,O=test");
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 10 * 365L * 24 * 3600 * 1000);
        SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(caKP.getPublic().getEncoded());

        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                subject, BigInteger.ONE, notBefore, notAfter, subject, spki);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.digitalSignature));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(caKP.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA", "BC");
        gen.initialize(2048, new SecureRandom());
        return gen.generateKeyPair();
    }

    /**
     * Issues a leaf certificate signed by the given CA.
     *
     * <p>The issuer field is built from {@code caCert.getSubjectX500Principal().getEncoded()}
     * (raw DER bytes) rather than the RFC 2253 string form.  This preserves the CA cert's
     * original RDN encoding so that PKIX chain validation — which compares DER-encoded names —
     * finds an exact match between the issued cert's issuer and the CA cert's subject.
     *
     * @param addLocalhostSan when {@code true}, adds a {@code dNSName=localhost} SAN extension
     *                        and {@code EKU=serverAuth}; when {@code false} adds
     *                        {@code EKU=clientAuth}.
     */
    static X509Certificate issueCert(
            String subjectDN, PublicKey pub,
            PrivateKey caKey, X509Certificate caCert,
            BigInteger serial, boolean addLocalhostSan) throws Exception {

        // Preserve the exact DER encoding of the CA's subject so that PKIX chain
        // validation can match the issuer field byte-for-byte.
        // X500Principal.getName() returns RFC 2253 which reverses RDN order;
        // getEncoded() returns the raw DER bytes, avoiding that pitfall.
        X500Name issuer = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
        X500Name subject = new X500Name(subjectDN);
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 3600 * 1000);
        SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(pub.getEncoded());

        X509v3CertificateBuilder builder =
                new X509v3CertificateBuilder(issuer, serial, notBefore, notAfter, subject, spki);

        // Standard leaf-cert extensions.  Without BasicConstraints=CA:FALSE and a
        // KeyUsage that includes Digital Signature, some JDK PKIX implementations
        // send certificate_unknown during mTLS handshake.
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        if (addLocalhostSan) {
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        } else {
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
        }

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(caKey);
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    /**
     * Writes a PEM-encoded certificate atomically: encodes into a byte array, writes
     * to a {@code .tmp} sibling file, then renames it to the destination.
     *
     * <p>The atomic rename produces a single, reliable {@code ENTRY_CREATE} or
     * {@code ENTRY_MODIFY} event for the WatchService, even on macOS where
     * in-place overwrites may not always trigger a modify notification promptly.
     */
    static void writeCert(X509Certificate cert, Path dest) throws Exception {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JcaPEMWriter w = new JcaPEMWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            w.writeObject(cert);
        }
        Files.write(tmp, baos.toByteArray());
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Writes a PEM-encoded private key atomically using the same temp-file strategy
     * as {@link #writeCert}.
     */
    static void writeKey(PrivateKey key, Path dest) throws Exception {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JcaPEMWriter w = new JcaPEMWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            w.writeObject(key);
        }
        Files.write(tmp, baos.toByteArray());
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }
}

package jp.broadcom.tanzu.mhoshi.server.cf;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.grpc.server.security.GrpcAuthenticationExtractor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

class CfIdentityExtractor implements GrpcAuthenticationExtractor {

    public static final Metadata.Key<String> METADATA_KEY = Metadata.Key.of("x-forwarded-client-cert", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public @Nullable Authentication extract(Metadata headers, Attributes attributes, MethodDescriptor<?, ?> method) {
        String auth = headers.get(METADATA_KEY);
        byte[] decodedCert = Base64.getDecoder().decode(auth);

        // 2. Initialize CertificateFactory for X.509
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }

        // 3. Generate the certificate object
        X509Certificate certificate;
        try {
            certificate = (X509Certificate) certFactory.generateCertificate(
                    new ByteArrayInputStream(decodedCert)
            );
        } catch (CertificateException e) {
            throw new RuntimeException(e);
        }
        return new PreAuthenticatedAuthenticationToken(new CfCertificate(certificate).subject(), auth);


    }
}

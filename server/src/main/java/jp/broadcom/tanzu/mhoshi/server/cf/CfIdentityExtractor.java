package jp.broadcom.tanzu.mhoshi.server.cf;

import org.springframework.security.web.authentication.preauth.x509.X509PrincipalExtractor;

import java.security.cert.X509Certificate;

class CfIdentityExtractor implements X509PrincipalExtractor {

    @Override
    public Object extractPrincipal(X509Certificate clientCert) {
        return new CfCertificate(clientCert).subject();
    }

}

package jp.broadcom.tanzu.mhoshi.server.cf;

import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.web.authentication.preauth.x509.X509PrincipalExtractor;

class CfIdentityExtractor implements X509PrincipalExtractor {

	@Override
	public Object extractPrincipal(X509Certificate clientCert) {
		return new CfCertificate(clientCert).subject();
	}

}

package jp.broadcom.tanzu.mhoshi.server.cf;

import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record CfCertificate(
        String subject,
        String appGuid,
        String spaceGuid,
        String organizationGuid
) {

    private static final String UUID_REGEX = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";
    private static final Pattern APP_PATTERN = Pattern.compile("OU=app:" + UUID_REGEX, Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACE_PATTERN = Pattern.compile("OU=space:" + UUID_REGEX, Pattern.CASE_INSENSITIVE);
    private static final Pattern ORG_PATTERN = Pattern.compile("OU=organization:" + UUID_REGEX, Pattern.CASE_INSENSITIVE);

    CfCertificate(X509Certificate clientCert) {
        this(clientCert.getSubjectX500Principal().getName());
    }

    CfCertificate(String subject) {
        this(subject, extractGuid(APP_PATTERN, subject), extractGuid(SPACE_PATTERN, subject), extractGuid(ORG_PATTERN, subject));
    }

    private static String extractGuid(Pattern pattern, String subject) {
        Matcher matcher = pattern.matcher(subject);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public boolean matchesSpace(CfCertificate other) {
        if (other == null) return false;
        if (this.spaceGuid == null || this.organizationGuid == null) return false;
        return Objects.equals(this.spaceGuid, other.spaceGuid) &&
                Objects.equals(this.organizationGuid, other.organizationGuid);
    }
}


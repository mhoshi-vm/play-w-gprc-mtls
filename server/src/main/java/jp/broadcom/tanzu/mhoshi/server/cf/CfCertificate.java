package jp.broadcom.tanzu.mhoshi.server.cf;

import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CfCertificate(
        String subject,
        String appGuid,
        String spaceGuid,
        String organizationGuid
) {
    private static final Pattern APP_PATTERN = Pattern.compile("OU=app:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final Pattern SPACE_PATTERN = Pattern.compile("OU=space:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final Pattern ORG_PATTERN = Pattern.compile("OU=organization:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    CfCertificate(X509Certificate clientCert) {
        // 2. The logic must be inline or via static helper methods inside this()
        this(
            clientCert.getSubjectX500Principal().getName()
        );
    }

    CfCertificate(String  subject) {
        // 2. The logic must be inline or via static helper methods inside this()
        this(
            subject,
            extractGuid(APP_PATTERN, subject),
            extractGuid(SPACE_PATTERN, subject),
            extractGuid(ORG_PATTERN, subject)
        );
    }

    boolean matchesSpace(CfCertificate other) {
        if (other == null) {
            return false;
        }
        // We use Objects.equals for Strings/Objects to handle nulls safely
        // We use == for primitives like int or double (though we aren't comparing price here)
        return Objects.equals(this.spaceGuid, other.spaceGuid) &&
               Objects.equals(this.organizationGuid, other.organizationGuid);
    }

    private static String extractGuid(Pattern pattern, String subject) {
        Matcher matcher = pattern.matcher(subject);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return "";
        }
    }
}


package jp.broadcom.tanzu.mhoshi.server.cf;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.grpc.server.security.GrpcAuthenticationExtractor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

class CfIdentityExtractor implements GrpcAuthenticationExtractor {

    private static final Pattern SUBJECT = Pattern.compile("Subject=\"([^\"]*)\"");
    public static final Metadata.Key<String> METADATA_KEY = Metadata.Key.of("x-forwarded-client-cert", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public @Nullable Authentication extract(Metadata headers, Attributes attributes, MethodDescriptor<?, ?> method) {
        String auth = headers.get(METADATA_KEY);
        if (auth == null) return null;
        Matcher matcher = SUBJECT.matcher(auth);
        if (matcher.find()) {
            return new PreAuthenticatedAuthenticationToken(matcher.group(1), auth);
        }
        return null;

    }
}

package jp.broadcom.tanzu.mhoshi.server.cf;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.grpc.server.security.SslContextPreAuthenticationExtractor;
import org.springframework.security.config.Customizer;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.security.KeyStore;
import java.security.cert.X509Certificate;

@Configuration
class CfSecurityConfig {

    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor jwtSecurityFilterChain(GrpcSecurity grpc) throws Exception {
        return grpc
                .authorizeRequests(requests -> requests
                        .methods("Simple/SayHello").hasAnyAuthority("ROLE_APP")
                        .allRequests().permitAll())
                .authenticationExtractor(new SslContextPreAuthenticationExtractor(new CfIdentityExtractor()))
                .preauth(Customizer.withDefaults())
                .build();
    }

    @Bean
    CfCertificate serverCfCertificate(SslBundles sslBundles) {
        SslBundle bundle = sslBundles.getBundle("self-signed");
        KeyStore keyStore = bundle.getStores().getKeyStore();

        try {
            String alias = "self-signed";
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
            return new CfCertificate(cert);
        } catch (Exception e) {
            return null;
        }
    }

    @Bean
    public UserDetailsService userDetailsService(CfCertificate serverCertificate) {
        return username -> CfIdentity.of(username, serverCertificate);
    }
}

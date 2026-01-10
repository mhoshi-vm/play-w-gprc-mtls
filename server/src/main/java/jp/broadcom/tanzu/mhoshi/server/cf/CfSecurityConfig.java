package jp.broadcom.tanzu.mhoshi.server.cf;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.grpc.server.security.SslContextPreAuthenticationExtractor;
import org.springframework.security.config.Customizer;
import org.springframework.security.core.userdetails.UserDetailsService;

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
    String allowedSpace(){
        return "6755b19d-c543-4e0c-a4b3-cd6e7c9c68a3";
    }

    @Bean
    public UserDetailsService userDetailsService(String allowedSpace) {
        return username -> CfIdentity.of(username, allowedSpace);
    }
}

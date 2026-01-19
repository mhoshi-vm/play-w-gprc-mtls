package jp.broadcom.tanzu.mhoshi.client;

import io.grpc.NameResolverRegistry;
import jakarta.annotation.PostConstruct;
import jp.broadcom.tanzu.mhoshi.server.proto.SimpleGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
class ClientConfiguration {
    @Bean
    SimpleGrpc.SimpleBlockingStub stub(GrpcChannelFactory channels) {
        return SimpleGrpc.newBlockingStub(channels.createChannel("local"));
    }

    @PostConstruct
    public void init() {
        // Register the custom provider globally
        NameResolverRegistry.getDefaultRegistry().register(new PreferIPNameResolverProvider());
    }
}

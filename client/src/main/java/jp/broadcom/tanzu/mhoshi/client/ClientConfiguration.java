package jp.broadcom.tanzu.mhoshi.client;

import jp.broadcom.tanzu.mhoshi.server.proto.SimpleGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

import java.security.*;

@Configuration
class ClientConfiguration {

    @Bean
    SimpleGrpc.SimpleBlockingStub stub(GrpcChannelFactory channels) {
        return SimpleGrpc.newBlockingStub(channels.createChannel("local"));
    }
}

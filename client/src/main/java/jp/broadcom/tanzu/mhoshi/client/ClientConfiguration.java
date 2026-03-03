package jp.broadcom.tanzu.mhoshi.client;

import io.grpc.ManagedChannel;
import jp.broadcom.tanzu.mhoshi.server.proto.SimpleGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

import java.security.*;

@Configuration
class ClientConfiguration {

    @Bean
    ManagedChannel localChannel(GrpcChannelFactory channels) {
        return channels.createChannel("local");
    }

    @Bean
    SimpleGrpc.SimpleBlockingStub stub(ManagedChannel localChannel) {
        return SimpleGrpc.newBlockingStub(localChannel);
    }
}

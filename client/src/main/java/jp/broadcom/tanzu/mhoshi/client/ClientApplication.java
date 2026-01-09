package jp.broadcom.tanzu.mhoshi.client;

import jp.broadcom.tanzu.mhoshi.server.proto.HelloReply;
import jp.broadcom.tanzu.mhoshi.server.proto.HelloRequest;
import jp.broadcom.tanzu.mhoshi.server.proto.SimpleGrpc;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@SpringBootApplication
public class ClientApplication implements CommandLineRunner {

    SimpleGrpc.SimpleBlockingStub stub;

    public ClientApplication(SimpleGrpc.SimpleBlockingStub stub) {
        this.stub = stub;
    }

    public static void main(String[] args) {
        SpringApplication.run(ClientApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        HelloReply reply = stub.sayHello(HelloRequest.newBuilder().setName("hello").build());
        System.out.println(reply.getMessage());
    }
}

@Configuration
class ClientConfiguration {
    @Bean
    SimpleGrpc.SimpleBlockingStub stub(GrpcChannelFactory channels) {
        return SimpleGrpc.newBlockingStub(channels.createChannel("local"));
    }
}
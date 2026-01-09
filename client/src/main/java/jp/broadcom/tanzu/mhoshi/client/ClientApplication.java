package jp.broadcom.tanzu.mhoshi.client;

import io.grpc.NameResolverRegistry;
import jakarta.annotation.PostConstruct;
import jp.broadcom.tanzu.mhoshi.client.cf.PreferIPNameResolverProvider;
import jp.broadcom.tanzu.mhoshi.server.proto.HelloReply;
import jp.broadcom.tanzu.mhoshi.server.proto.HelloRequest;
import jp.broadcom.tanzu.mhoshi.server.proto.SimpleGrpc;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

import java.util.Iterator;

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
        System.out.println(stub.sayHello(HelloRequest.newBuilder().setName("hello").build()));
//        Iterator<HelloReply> iterator = stub.streamHello(HelloRequest.newBuilder().setName("hello2").build());
//        while (iterator.hasNext()) {
//            // Read the current message and advance the iterator
//            String message = iterator.next().getMessage();
//            System.out.println("Message: " + message);
//        }

        try {
            System.out.println(stub.sayHello(HelloRequest.newBuilder().setName("error").build()));
        }catch (Exception e){
            e.printStackTrace();
        }
        try {
          System.out.println(stub.sayHello(HelloRequest.newBuilder().setName("internal").build()));
        }catch (Exception e){
            e.printStackTrace();
        }

    }
}

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
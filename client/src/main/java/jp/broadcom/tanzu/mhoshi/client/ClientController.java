package jp.broadcom.tanzu.mhoshi.client;

import jp.broadcom.tanzu.mhoshi.server.proto.HelloRequest;
import jp.broadcom.tanzu.mhoshi.server.proto.SimpleGrpc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ClientController {
    SimpleGrpc.SimpleBlockingStub stub;
    ;

    public ClientController(SimpleGrpc.SimpleBlockingStub stub) {
        this.stub = stub;
    }

    @GetMapping
    public void run() {
        System.out.println(stub.sayHello(HelloRequest.newBuilder().setName("hello").build()));


//        Iterator<HelloReply> iterator = stub.streamHello(HelloRequest.newBuilder().setName("hello2").build());
//        while (iterator.hasNext()) {
//            // Read the current message and advance the iterator
//            String message = iterator.next().getMessage();
//            System.out.println("Message: " + message);
//        }

//        try {
//            System.out.println(stub.sayHello(HelloRequest.newBuilder().setName("error").build()));
//        }catch (Exception e){
//            e.printStackTrace();
//        }
//        try {
//          System.out.println(stub.sayHello(HelloRequest.newBuilder().setName("internal").build()));
//        }catch (Exception e){
//            e.printStackTrace();
//        }

    }

}

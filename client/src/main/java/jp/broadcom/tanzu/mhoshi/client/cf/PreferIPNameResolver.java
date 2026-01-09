package jp.broadcom.tanzu.mhoshi.client.cf;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusOr;

import java.net.*;
import java.util.Collections;

class PreferIPNameResolver extends NameResolver {


    private Listener2 listener;
    private int port = 9090;
    private String ip;

    PreferIPNameResolver(URI targetUri) {
        String authority = targetUri.getAuthority();
        String host = "localhost";
        if (authority != null) {
            String[] authorityPort  = authority.split(":");
            host = authorityPort[0];
            port = authorityPort.length > 1 ? Integer.parseInt(authorityPort[1]): 9090;
        }
        try {
            ip = InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            ip = "127.0.0.1";
        }
    }

    @Override
    public String getServiceAuthority() {
        return ip;
    }

    @Override
    public void start(Listener2 listener) {
        this.listener = listener;
        resolve();
    }

    @Override
    public void refresh() {
        resolve();
    }

    private void resolve() {
        try {

            // --- YOUR CUSTOM LOGIC HERE ---
            // Example: "my-service" maps to localhost:9090
            // In reality, you might query a DB, K8s, or Consul here.
            SocketAddress socketAddress = new InetSocketAddress(ip, port);

            // Wrap it in an EquivalentAddressGroup (EAG)
            EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(socketAddress);

            // Pass the result to the listener as a ResolutionResult
            ResolutionResult result = ResolutionResult.newBuilder()
                    .setAddressesOrError(StatusOr.fromValue(Collections.singletonList(addressGroup)))
                    .build();

            listener.onResult(result);

        } catch (Exception e) {
            // Notify gRPC of the error
            listener.onError(Status.UNAVAILABLE.withDescription("Failed to resolve: " + e.getMessage()));
        }
    }

    @Override
    public void shutdown() {
        // Clean up resources (e.g., stop background threads/timers)
    }
}
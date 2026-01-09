package jp.broadcom.tanzu.mhoshi.client.cf;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

class PreferIPNameResolver extends NameResolver {

    private final String authority;
    private Listener2 listener;

    PreferIPNameResolver(URI targetUri) {
        this.authority = targetUri.getAuthority();
    }

    @Override
    public String getServiceAuthority() {
        return authority;
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

            String[] authorityPort;
            authorityPort = authority.splitWithDelimiters(":", 2);

            String host = authorityPort[0];
            String port = authorityPort.length == 3 ? authorityPort[2] : "9090";
            String ip = InetAddress.getByName(host).getHostAddress();

            SocketAddress socketAddress = new InetSocketAddress(ip, Integer.parseInt(port));

            // Wrap it in an EquivalentAddressGroup (EAG)
            EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(socketAddress);

            // Pass the result to the listener as a ResolutionResult
            ResolutionResult result = ResolutionResult.newBuilder()
                    .setAddresses(Collections.singletonList(addressGroup))
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
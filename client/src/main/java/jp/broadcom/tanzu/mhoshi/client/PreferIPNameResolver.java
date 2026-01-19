package jp.broadcom.tanzu.mhoshi.client;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusOr;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;

class PreferIPNameResolver extends NameResolver {

    private final String host;
    private final int port;
    // Volatile ensures that if 'refresh()' updates the IP, other threads see it immediately
    private volatile String ip;
    private Listener2 listener;

    PreferIPNameResolver(URI targetUri) {
        String authority = targetUri.getAuthority();
        String parsedHost = "localhost";
        int parsedPort = 9090;

        if (authority != null) {
            String[] authorityPort = authority.split(":");
            parsedHost = authorityPort[0];
            parsedPort = authorityPort.length > 1 ? Integer.parseInt(authorityPort[1]) : 9090;
        }

        this.host = parsedHost;
        this.port = parsedPort;

        // 1. BLOCKING RESOLUTION (Required for your use case)
        // We must resolve here so 'ip' is available for getServiceAuthority() immediately.
        resolveInternal(true);
    }

    @Override
    public String getServiceAuthority() {
        // 2. Returns the IP address as requested
        return ip;
    }

    @Override
    public void start(Listener2 listener) {
        this.listener = listener;
        // Push the result we calculated in the constructor
        pushResult();
    }

    @Override
    public void refresh() {
        // 3. On refresh, we re-resolve to get the latest IP (in case DNS changed)
        resolveInternal(false);
        pushResult();
    }

    // Helper to perform the DNS lookup
    private void resolveInternal(boolean isConstructor) {
        try {
            // This performs a DNS lookup (blocking)
            InetSocketAddress tempAddr = new InetSocketAddress(host, port);
            if (!tempAddr.isUnresolved()) {
                this.ip = tempAddr.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            listener.onError(Status.UNAVAILABLE.withDescription("DNS Resolution failed: " + e.getMessage()));
        }
    }

    private void pushResult() {
        if (listener == null) return;

        try {
            // Use the stored 'ip' to create the connection target
            InetSocketAddress socketAddress = new InetSocketAddress(ip, port);
            EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(socketAddress);

            ResolutionResult result = ResolutionResult.newBuilder()
                    .setAddressesOrError(StatusOr.fromValue(Collections.singletonList(addressGroup)))
                    .build();

            listener.onResult(result);
        } catch (Exception e) {
            listener.onError(Status.UNAVAILABLE.withDescription("Failed to construct address: " + e.getMessage()));
        }
    }

    @Override
    public void shutdown() {
        // No resources to clean up in synchronous mode
    }
}
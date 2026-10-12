package tz.co.msingi.cims.integration.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.util.List;

/**
 * Request factory for hosts that resolve to several A-records where some are
 * unreachable. The JDK HTTP stack only tries the first resolved address, so it
 * can hang on a dead IP. This probes the resolved addresses, pins requests to a
 * reachable one, and keeps TLS (SNI + certificate verification) and the HTTP
 * {@code Host} header bound to the original hostname so the call stays secure
 * and routes correctly. Used for {@code www.nacte.go.tz} (taken from NOAS).
 */
public final class ReachableIpRequestFactory extends SimpleClientHttpRequestFactory {

    static {
        // allow overriding the restricted "Host" header when we pin to an IP
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private final String host;
    private final String preferIp;
    private volatile String cachedIp;

    public ReachableIpRequestFactory(String host, String preferIp) {
        this.host = host;
        this.preferIp = (preferIp == null || preferIp.isBlank()) ? null : preferIp.trim();
    }

    @Override
    protected HttpURLConnection openConnection(URL url, Proxy proxy) throws IOException {
        if (host == null || !host.equalsIgnoreCase(url.getHost())) {
            return super.openConnection(url, proxy);
        }
        String ip = reachableIp();
        if (ip == null) {
            return super.openConnection(url, proxy);
        }
        URL pinned = new URL(url.getProtocol(), ip, url.getPort(), url.getFile());
        HttpURLConnection conn = (HttpURLConnection)
                (proxy != null ? pinned.openConnection(proxy) : pinned.openConnection());
        conn.setRequestProperty("Host", host);
        if (conn instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(new SniSslSocketFactory(host));
            HostnameVerifier dflt = HttpsURLConnection.getDefaultHostnameVerifier();
            https.setHostnameVerifier((h, session) -> dflt.verify(host, session));
        }
        return conn;
    }

    private String reachableIp() {
        if (preferIp != null) return preferIp;
        String c = cachedIp;
        if (c != null) return c;
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(a, 443), 3000);
                    cachedIp = a.getHostAddress();
                    return cachedIp;
                } catch (IOException ignore) {
                    // try the next address
                }
            }
        } catch (Exception ignore) {
            // fall back to default resolution
        }
        return null;
    }

    /** Forces SNI + endpoint identification to the real hostname, not the pinned IP. */
    static final class SniSslSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        private final String sniHost;

        SniSslSocketFactory(String sniHost) {
            this.sniHost = sniHost;
        }

        private Socket apply(Socket socket) {
            if (socket instanceof SSLSocket ssl) {
                SSLParameters params = ssl.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(sniHost)));
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
            }
            return socket;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return apply(delegate.createSocket(s, sniHost, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return apply(delegate.createSocket(sniHost, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return apply(delegate.createSocket(sniHost, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return apply(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return apply(delegate.createSocket(address, port, localAddress, localPort));
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }
}

package org.familyhealthcare.service;

import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import java.net.*;
import java.util.Locale;

/** Resolve once, reject the entire answer if any address is unsafe, then carry immutable pins to transport. */
public final class WebhookTargetPolicy {
    public static final String REJECTION = "The webhook URL must use HTTPS and resolve only to public addresses.";
    private final DnsResolver resolver;
    public WebhookTargetPolicy() { this(SystemDefaultDnsResolver.INSTANCE); }
    public WebhookTargetPolicy(DnsResolver resolver) { this.resolver = java.util.Objects.requireNonNull(resolver); }
    public Target validate(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535)
                throw rejected();
            String dnsHost = normalizeHost(host);
            if (dnsHost.contains("%")) throw rejected();
            InetAddress[] addresses = resolver.resolve(dnsHost);
            if (addresses == null || addresses.length == 0) throw rejected();
            for (InetAddress address : addresses) if (!isPublicAddress(address)) throw rejected();
            return new Target(uri, dnsHost, addresses);
        } catch (URISyntaxException | UnknownHostException | NullPointerException invalid) { throw rejected(); }
    }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException(REJECTION); }
    static String normalizeHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        return value;
    }
    /** Conservative public-unicast policy: special-use, translation and tunnelling ranges fail closed. */
    static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a=b[0]&255, c=b[1]&255, d=b[2]&255;
            return !(a==0 || a==10 || a==127 || a>=224 || (a==100 && c>=64 && c<=127)
                    || (a==169 && c==254) || (a==172 && c>=16 && c<=31)
                    || (a==192 && ((c==0 && (d==0 || d==2)) || (c==88 && d==99) || c==168))
                    || (a==198 && (c==18 || c==19 || (c==51 && d==100))) || (a==203 && c==0 && d==113));
        }
        if (b.length != 16 || (b[0]&0xe0)!=0x20 || ((Inet6Address)address).getScopeId()!=0) return false;
        int a=b[0]&255,c=b[1]&255,d=b[2]&255,e=b[3]&255;
        return !((a==0x20 && c==0x01 && (d<=1 || (d==0x0d && e==0xb8)))
                || (a==0x20 && c==0x02) || (a==0x3f && c==0xff && (d&0xf0)==0));
    }
    public static final class Target {
        private final URI uri; private final String host; private final InetAddress[] addresses;
        private Target(URI uri,String host,InetAddress[] addresses) { this.uri=uri;this.host=host;this.addresses=addresses.clone(); }
        public URI getUri() { return uri; }
        public String getHost() { return host; }
        public InetAddress[] getAddresses() { return addresses.clone(); }
    }
}

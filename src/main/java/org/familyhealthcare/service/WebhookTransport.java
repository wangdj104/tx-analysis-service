package org.familyhealthcare.service;

import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Map;

/** Original URI/Host/TLS hostname are retained; only the connection's DNS answer is replaced by approved pins. */
public class WebhookTransport {
    private final ConnectionSocketFactory https;
    public WebhookTransport() { this(SSLConnectionSocketFactory.getSocketFactory()); }
    public WebhookTransport(ConnectionSocketFactory https) { this.https=java.util.Objects.requireNonNull(https); }
    public ResponseEntity<String> post(WebhookTargetPolicy.Target target, Map<String,Object> body) {
        DnsResolver pins = host -> {
            if (!WebhookTargetPolicy.normalizeHost(host).equals(target.getHost())) throw new UnknownHostException("Unapproved webhook host");
            return new java.net.InetAddress[]{target.getAddresses()[0]};
        };
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create().register("https",https).build(),pins);
        try (CloseableHttpClient client=HttpClients.custom().setConnectionManager(manager)
                .disableAutomaticRetries().disableRedirectHandling().build()) {
            HttpComponentsClientHttpRequestFactory factory=new HttpComponentsClientHttpRequestFactory(client);
            factory.setConnectTimeout(5000);factory.setReadTimeout(5000);factory.setConnectionRequestTimeout(5000);
            HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
            return new RestTemplate(factory).postForEntity(target.getUri(),new HttpEntity<>(body,headers),String.class);
        } catch (IOException closing) { throw new org.springframework.web.client.ResourceAccessException("Notification transport outcome is uncertain.",closing); }
        finally { manager.close(); }
    }
}

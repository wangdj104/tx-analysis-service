package org.familyhealthcare.service;
import org.apache.http.HttpHost;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
/** Drives the real Apache client/connection operator; fake sockets never leave the process. */
class WebhookTransportConnectionTest {
    static final class WireSocket extends Socket {
        InetSocketAddress connectedTo;
        @Override public void connect(SocketAddress address,int timeout){connectedTo=(InetSocketAddress)address;}
        final InputStream input;final ByteArrayOutputStream output=new ByteArrayOutputStream();
        WireSocket(String response){input=new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII));}
        @Override public InputStream getInputStream(){return input;} @Override public OutputStream getOutputStream(){return output;}
        @Override public boolean isConnected(){return true;} @Override public boolean isClosed(){return false;}
        @Override public void setSoTimeout(int timeout){} @Override public void setTcpNoDelay(boolean value){} @Override public void setKeepAlive(boolean value){} @Override public void close(){}
    }
    static final class SocketProbe implements ConnectionSocketFactory {
        int attempts;HttpHost original;InetSocketAddress destination;WireSocket socket;boolean fail;
        final String response;
        SocketProbe(String response){this.response=response;}
        public Socket createSocket(HttpContext c){socket=new WireSocket(response);return socket;}
        public Socket connectSocket(int timeout,Socket sock,HttpHost host,InetSocketAddress remote,InetSocketAddress local,HttpContext context)throws IOException{
            attempts++;original=host;destination=remote;assertEquals(5000,timeout);if(fail)throw new SocketTimeoutException("synthetic timeout");return sock;
        }
    }
    WebhookTargetPolicy.Target target(AtomicInteger calls)throws Exception{
        InetAddress publicIp=InetAddress.getByAddress(new byte[]{93,(byte)184,(byte)216,34});
        return new WebhookTargetPolicy(host->{assertEquals("synthetic.test",host);if(calls.incrementAndGet()>1)return new InetAddress[]{InetAddress.getLoopbackAddress()};return new InetAddress[]{publicIp};}).validate("https://synthetic.test/hook?token=synthetic");
    }
    @Test void approvedAddressReachesActualConnectionAndOriginalTlsHostnameIsRetained()throws Exception{
        AtomicInteger dns=new AtomicInteger();SocketProbe probe=new SocketProbe("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");
        assertEquals(200,new WebhookTransport(probe).post(target(dns),Collections.singletonMap("title","synthetic")).getStatusCodeValue());
        assertEquals(1,dns.get());assertEquals(1,probe.attempts);assertEquals("93.184.216.34",probe.destination.getAddress().getHostAddress());assertEquals(443,probe.destination.getPort());
        assertEquals("synthetic.test",probe.original.getHostName());assertEquals("https",probe.original.getSchemeName());
        String request=probe.socket.output.toString("UTF-8");assertTrue(request.contains("Host: synthetic.test"));assertTrue(request.contains("POST /hook?token=synthetic"));assertFalse(request.contains("127.0.0.1"));
    }
    @Test void redirectIsReturnedWithoutFollowingEvenToPrivateDestination()throws Exception{
        AtomicInteger dns=new AtomicInteger();SocketProbe probe=new SocketProbe("HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1/private\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        assertEquals(302,new WebhookTransport(probe).post(target(dns),Collections.singletonMap("title","synthetic")).getStatusCodeValue());assertEquals(1,probe.attempts);assertEquals(1,dns.get());
    }
    @Test void timeoutIsAttemptedOnceAndNeverRetried()throws Exception{
        AtomicInteger dns=new AtomicInteger();SocketProbe probe=new SocketProbe("");probe.fail=true;
        WebhookTargetPolicy.Target target=target(dns);assertThrows(ResourceAccessException.class,()->new WebhookTransport(probe).post(target,Collections.singletonMap("title","synthetic")));
        assertEquals(1,probe.attempts);assertEquals(1,dns.get());
    }
    @Test void realSslConnectionFactoryPassesTheOriginalHostnameToTlsLayering()throws Exception {
        class TlsProbe extends org.apache.http.conn.ssl.SSLConnectionSocketFactory {
            WireSocket socket;String layeredHostname;
            TlsProbe()throws Exception{super(javax.net.ssl.SSLContext.getDefault());}
            @Override public Socket createSocket(HttpContext context){socket=new WireSocket("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");return socket;}
            @Override public Socket createLayeredSocket(Socket socket,String hostname,int port,HttpContext context){layeredHostname=hostname;return socket;}
        }
        TlsProbe probe=new TlsProbe();AtomicInteger dns=new AtomicInteger();
        assertEquals(200,new WebhookTransport(probe).post(target(dns),Collections.singletonMap("title","synthetic")).getStatusCodeValue());
        assertEquals("synthetic.test",probe.layeredHostname);assertEquals("93.184.216.34",probe.socket.connectedTo.getAddress().getHostAddress());assertEquals(1,dns.get());
    }
}

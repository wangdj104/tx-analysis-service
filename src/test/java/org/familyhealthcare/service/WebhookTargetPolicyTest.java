package org.familyhealthcare.service;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class WebhookTargetPolicyTest {
    private InetAddress ip(String value)throws Exception{return InetAddress.getByName(value);}
    @Test void everyPrivateSpecialUseAndReservedRangeIsRejected()throws Exception {
        for(String value:new String[]{"0.1.2.3","10.1.2.3","100.64.0.1","127.0.0.1","169.254.169.254","172.16.0.1","172.31.255.255","192.0.0.1","192.0.2.1","192.88.99.1","192.168.1.1","198.18.0.1","198.19.255.255","198.51.100.1","203.0.113.1","224.0.0.1","240.0.0.1","255.255.255.255","::","::1","::ffff:127.0.0.1","fc00::1","fe80::1","ff02::1","64:ff9b::a00:1","100::1","2001::1","2001:2::1","2001:db8::1","2001:20::1","2002:7f00:1::1","3fff::1","4000::1"}) {
            InetAddress address=ip(value);WebhookTargetPolicy p=new WebhookTargetPolicy(host->new InetAddress[]{address});
            assertThrows(IllegalArgumentException.class,()->p.validate("https://synthetic.test/hook"),value);
        }
    }
    @Test void mixedDnsFailsClosedAndResolvedPinsAreImmutable()throws Exception{
        InetAddress publicIp=ip("93.184.216.34"),privateIp=ip("10.0.0.1");
        assertThrows(IllegalArgumentException.class,()->new WebhookTargetPolicy(host->new InetAddress[]{publicIp,privateIp}).validate("https://synthetic.test/hook"));
        InetAddress[] source={publicIp,ip("2606:4700:4700::1111")};AtomicInteger calls=new AtomicInteger();
        WebhookTargetPolicy p=new WebhookTargetPolicy(host->{calls.incrementAndGet();return source;});
        WebhookTargetPolicy.Target target=p.validate("https://synthetic.test/hook");source[0]=privateIp;target.getAddresses()[0]=privateIp;
        assertEquals(publicIp,target.getAddresses()[0]);assertEquals(1,calls.get());
    }
    @Test void malformedNonHttpsUserInfoFragmentAndUnknownDnsNeverProduceTarget(){
        WebhookTargetPolicy p=new WebhookTargetPolicy(host->{throw new UnknownHostException("synthetic unavailable");});
        for(String value:new String[]{null,"","not a url","http://synthetic.test/hook","https://user:password@synthetic.test/hook","https://synthetic.test/hook#fragment","https://synthetic.test:0/hook","https://synthetic.test:65536/hook","https://synthetic.test/hook","https://[fe80::1%25eth0]/"})assertThrows(IllegalArgumentException.class,()->p.validate(value));
    }
}

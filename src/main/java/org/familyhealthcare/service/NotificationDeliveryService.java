package org.familyhealthcare.service;

import org.familyhealthcare.entity.NotificationChannel;
import org.familyhealthcare.mapper.NotificationChannelMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.util.*;

@Service
public class NotificationDeliveryService {
    @Autowired private NotificationChannelMapper mapper;
    @Autowired private RobotChannelConfigService robots;
    private final RestTemplate client;

    public NotificationDeliveryService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        client = new RestTemplate(factory);
    }

    public void send(NotificationChannel channel, String title, String content) {
        URI uri;
        try { uri = URI.create(channel.getWebhookUrl()); }
        catch (Exception e) { throw new IllegalArgumentException("Configure a valid webhook URL."); }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("The webhook URL must begin with http:// or https://.");
        boolean ding="DINGTALK_WEBHOOK".equals(channel.getChannelType());
        if(ding){if(channel.getId()!=null)robots.load(channel);uri=dingTalkUri(uri,channel.getRobotSecret(),System.currentTimeMillis());}
        Map<String, Object> body = new LinkedHashMap<>();
        if (ding || "WECHAT_WEBHOOK".equals(channel.getChannelType())) {
            body.put("msgtype", "text");
            body.put("text", Collections.singletonMap("content", (ding&&channel.getRobotKeyword()!=null&&!channel.getRobotKeyword().isEmpty()?channel.getRobotKeyword()+"\n":"") + title + "\n" + content));
        } else if ("WEBHOOK".equals(channel.getChannelType())) {
            body.put("title", title); body.put("content", content);
        } else throw new IllegalArgumentException("Unsupported notification channel type.");
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = client.postForEntity(uri, new HttpEntity<>(body, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("Notification delivery failed: HTTP " + response.getStatusCodeValue());
        if (ding || "WECHAT_WEBHOOK".equals(channel.getChannelType())) {
            JSONObject result = JSON.parseObject(response.getBody());
            if (result == null || !Integer.valueOf(0).equals(result.getInteger("errcode")))
                throw new IllegalStateException("The bot rejected the message. Check the webhook URL, keyword, and signature settings.");
        }
    }

    static URI dingTalkUri(URI uri,String secret,long timestamp) {
        if(secret==null||secret.trim().isEmpty())return uri;
        try{javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
            String signature=java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(mac.doFinal((timestamp+"\n"+secret.trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8))),"UTF-8");
            return URI.create(uri.toString()+(uri.getRawQuery()==null?"?":"&")+"timestamp="+timestamp+"&sign="+signature);
        }catch(Exception e){throw new IllegalArgumentException("Failed to sign the DingTalk request. Check the signing secret.");}
    }

    public boolean notifyUser(Long userId, String title, String content) {
        return notifyUser(userId, Collections.emptyList(), title, content);
    }

    /** When channelIds is empty, send to every enabled channel owned by the user. */
    public boolean notifyUser(Long userId, Collection<Long> channelIds, String title, String content) {
        boolean success = true;
        QueryWrapper<NotificationChannel> query = new QueryWrapper<NotificationChannel>().eq("user_id", userId).eq("enabled", 1);
        if (channelIds != null && !channelIds.isEmpty()) query.in("id", channelIds);
        List<NotificationChannel> channels = mapper.selectList(query);
        if (channels.isEmpty()) return false;
        for (NotificationChannel channel : channels) {
            try { send(channel, title, content); }
            catch (Exception e) {
                success = false;
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Notification delivery failed, channelId={}", channel.getId(), e);
            }
        }
        return success;
    }
}

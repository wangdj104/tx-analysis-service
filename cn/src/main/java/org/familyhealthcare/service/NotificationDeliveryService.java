package org.familyhealthcare.service;

import org.familyhealthcare.entity.NotificationChannel;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryAttempt;
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
    @Autowired private UserLanguagePreferenceService languagePreference;
    @Autowired private NotificationMessageLocalizer localizer;
    private final RestTemplate client;

    public NotificationDeliveryService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        client = new RestTemplate(factory);
    }

    public void send(NotificationChannel channel, String title, String content) {
        send(channel,title,content,true);
    }

    /** Care-plan messages must not acquire arbitrary provider configuration text after localization. */
    private void send(NotificationChannel channel,String title,String content,boolean prefixRobotKeyword) {
        URI uri;
        try { uri = URI.create(channel.getWebhookUrl()); }
        catch (Exception e) { throw new InvalidNotificationConfiguration("Configure a valid webhook URL."); }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
            throw new InvalidNotificationConfiguration("The webhook URL must begin with http:// or https://.");
        boolean ding="DINGTALK_WEBHOOK".equals(channel.getChannelType());
        if(ding){if(channel.getId()!=null)robots.load(channel);try{uri=dingTalkUri(uri,channel.getRobotSecret(),System.currentTimeMillis());}catch(IllegalArgumentException invalid){throw new InvalidNotificationConfiguration("Invalid notification signing configuration.");}}
        Map<String, Object> body = new LinkedHashMap<>();
        if (ding || "WECHAT_WEBHOOK".equals(channel.getChannelType())) {
            body.put("msgtype", "text");
            body.put("text", Collections.singletonMap("content", (prefixRobotKeyword&&ding&&channel.getRobotKeyword()!=null&&!channel.getRobotKeyword().isEmpty()?channel.getRobotKeyword()+"\n":"") + title + "\n" + content));
        } else if ("WEBHOOK".equals(channel.getChannelType())) {
            body.put("title", title); body.put("content", content);
        } else throw new InvalidNotificationConfiguration("Unsupported notification channel type.");
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = client.postForEntity(uri, new HttpEntity<>(body, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("Notification delivery failed: HTTP " + response.getStatusCodeValue());
        if (ding || "WECHAT_WEBHOOK".equals(channel.getChannelType())) {
            JSONObject result = JSON.parseObject(response.getBody());
            if(result==null||result.getInteger("errcode")==null)throw new IllegalStateException("Notification acknowledgement was unavailable.");
            if(!Integer.valueOf(0).equals(result.getInteger("errcode")))throw new KnownProviderRejection("The bot rejected the message. Check the webhook URL, keyword, and signature settings.");
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
        return notifyUser(userId, Collections.emptyList(), "GENERAL", title, content);
    }

    public boolean notifyUser(Long userId, String eventType, String title, String content) {
        return notifyUser(userId, Collections.emptyList(), eventType, title, content);
    }

    /** When channelIds is empty, send to every enabled channel owned by the user. */
    public boolean notifyUser(Long userId, Collection<Long> channelIds, String title, String content) {
        return notifyUser(userId, channelIds, "GENERAL", title, content);
    }

    public boolean notifyUser(Long userId, Collection<Long> channelIds, String eventType, String title, String content) {
        boolean success = false;
        QueryWrapper<NotificationChannel> query = new QueryWrapper<NotificationChannel>().eq("user_id", userId).eq("enabled", 1);
        if (channelIds != null && !channelIds.isEmpty()) query.in("id", channelIds);
        List<NotificationChannel> channels = mapper.selectList(query);
        if (channels.isEmpty()) return false;
        NotificationMessageLocalizer.Message message = localized(userId, eventType, title, content);
        for (NotificationChannel channel : channels) {
            try { send(channel, message.getTitle(), message.getContent()); success = true; }
            catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Notification delivery failed, channelId={}", channel.getId(), e);
            }
        }
        return success;
    }

    /** Compatibility API preserves the four outcome values; retryability is an independent fact. */
    public DeliveryOutcome deliverCarePlan(long channelId,String eventKey,String title,String relativePath) {
        return deliverCarePlanAttempt(channelId,eventKey,title,relativePath).getOutcome();
    }

    /** Only an explicit rate-limit rejection without unsupported server timing permits automatic retry. */
    public DeliveryAttempt deliverCarePlanAttempt(long channelId,String eventKey,String title,String relativePath) {
        try {
            NotificationChannel channel=mapper.selectById(channelId);
            if(channel==null||!Integer.valueOf(1).equals(channel.getEnabled()))return new DeliveryAttempt(DeliveryOutcome.NO_CHANNEL,false);
            if(eventKey==null||!eventKey.startsWith("CARE_PLAN_"))return new DeliveryAttempt(DeliveryOutcome.FAILED,false);
            String language=languagePreference==null?"en-US":languagePreference.get(channel.getUserId());
            NotificationMessageLocalizer.Message message=(localizer==null?new NotificationMessageLocalizer():localizer).localize(eventKey,title,relativePath,language);
            send(channel,message.getTitle(),message.getContent(),false);
            return new DeliveryAttempt(DeliveryOutcome.DELIVERED,false);
        } catch(org.springframework.web.client.ResourceAccessException ambiguous) {
            return new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
        } catch(org.springframework.web.client.RestClientResponseException response) {
            int status=response.getRawStatusCode();
            if(status==408||status>=500||status<400)return new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
            boolean retryable=status==429&&(response.getResponseHeaders()==null||!response.getResponseHeaders().containsKey("Retry-After"));
            return new DeliveryAttempt(DeliveryOutcome.FAILED,retryable);
        } catch(InvalidNotificationConfiguration|KnownProviderRejection definitive) {
            return new DeliveryAttempt(DeliveryOutcome.FAILED,false);
        } catch(RuntimeException ambiguous) {
            return new DeliveryAttempt(DeliveryOutcome.UNKNOWN,false);
        }
    }
    private static final class InvalidNotificationConfiguration extends IllegalArgumentException {
        InvalidNotificationConfiguration(String message){super(message);}
    }
    private static final class KnownProviderRejection extends IllegalStateException {
        KnownProviderRejection(String message){super(message);}
    }

    public void sendForUser(Long userId, NotificationChannel channel, String eventType, String title, String content) {
        NotificationMessageLocalizer.Message message = localized(userId, eventType, title, content);
        send(channel, message.getTitle(), message.getContent());
    }

    private NotificationMessageLocalizer.Message localized(Long userId, String eventType, String title, String content) {
        if (languagePreference == null || localizer == null) return new NotificationMessageLocalizer.Message(title, content);
        return localizer.localize(eventType, title, content, languagePreference.get(userId));
    }
}

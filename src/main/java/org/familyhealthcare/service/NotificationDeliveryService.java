package org.familyhealthcare.service;

import org.familyhealthcare.entity.NotificationChannel;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryOutcome;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport.DeliveryAttempt;
import org.familyhealthcare.mapper.NotificationChannelMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;

@Service
public class NotificationDeliveryService {
    @Autowired private NotificationChannelMapper mapper;
    @Autowired private RobotChannelConfigService robots;
    @Autowired private UserLanguagePreferenceService languagePreference;
    @Autowired private NotificationMessageLocalizer localizer;
    private final WebhookTargetPolicy targetPolicy;
    private final WebhookTransport transport;

    public NotificationDeliveryService() { this(new WebhookTargetPolicy(), new WebhookTransport()); }

    public NotificationDeliveryService(WebhookTargetPolicy targetPolicy, WebhookTransport transport) {
        this.targetPolicy=java.util.Objects.requireNonNull(targetPolicy);
        this.transport=java.util.Objects.requireNonNull(transport);
    }

    public void validateWebhookUrl(String value) { targetPolicy.validate(value); }

    public void send(NotificationChannel channel, String title, String content) {
        send(channel,title,content,true);
    }

    /** Care-plan messages must not acquire arbitrary provider configuration text after localization. */
    private void send(NotificationChannel channel,String title,String content,boolean prefixRobotKeyword) {
        URI uri;
        try { uri = URI.create(channel.getWebhookUrl()); }
        catch (Exception e) { throw new InvalidNotificationConfiguration("Configure a valid webhook URL."); }
        if (uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme()))
            throw new InvalidNotificationConfiguration(WebhookTargetPolicy.REJECTION);
        boolean ding="DINGTALK_WEBHOOK".equals(channel.getChannelType());
        if(ding){if(channel.getId()!=null)robots.load(channel);try{uri=dingTalkUri(uri,channel.getRobotSecret(),System.currentTimeMillis());}catch(IllegalArgumentException invalid){throw new InvalidNotificationConfiguration("Invalid notification signing configuration.");}}
        Map<String, Object> body = new LinkedHashMap<>();
        if (ding || "WECHAT_WEBHOOK".equals(channel.getChannelType())) {
            body.put("msgtype", "text");
            body.put("text", Collections.singletonMap("content", (prefixRobotKeyword&&ding&&channel.getRobotKeyword()!=null&&!channel.getRobotKeyword().isEmpty()?channel.getRobotKeyword()+"\n":"") + title + "\n" + content));
        } else if ("WEBHOOK".equals(channel.getChannelType())) {
            body.put("title", title); body.put("content", content);
        } else throw new InvalidNotificationConfiguration("Unsupported notification channel type.");
        final WebhookTargetPolicy.Target target;
        try { target=targetPolicy.validate(uri.toString()); }
        catch (IllegalArgumentException invalid) { throw new InvalidNotificationConfiguration(WebhookTargetPolicy.REJECTION); }
        ResponseEntity<String> response = transport.post(target, body);
        if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("Notification delivery failed: HTTP " + response.getStatusCodeValue());
        if (ding || "WECHAT_WEBHOOK".equals(channel.getChannelType())) {
            final com.fasterxml.jackson.databind.JsonNode acknowledgement;
            try {
                acknowledgement = new com.fasterxml.jackson.databind.ObjectMapper()
                        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(response.getBody());
            } catch (java.io.IOException malformed) { throw new IllegalStateException("Notification acknowledgement was unavailable."); }
            com.fasterxml.jackson.databind.JsonNode code=acknowledgement==null?null:acknowledgement.get("errcode");
            // Protocol integers only: coercion could truncate a fraction or wrap an overflow into success.
            if (acknowledgement==null || !acknowledgement.isObject() || code==null || !code.isIntegralNumber() || !code.canConvertToInt())
                throw new IllegalStateException("Notification acknowledgement was unavailable.");
            if(code.intValue()!=0)throw new KnownProviderRejection("The bot rejected the message. Check the webhook URL, keyword, and signature settings.");
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
        return notifyUserOutcomes(userId,channelIds,eventType,title,content).containsValue(DeliveryOutcome.DELIVERED);
    }

    /** Each channel is attempted once. An uncertain response must never be flattened into a retryable failure. */
    public Map<Long,DeliveryOutcome> notifyUserOutcomes(Long userId,Collection<Long> channelIds,String eventType,String title,String content) {
        return notifyUserOutcomes(userId,channelIds,eventType,title,content,(id,outcome)->{});
    }

    /** Journal observers run between channels; failed persistence aborts subsequent external attempts. */
    public Map<Long,DeliveryOutcome> notifyUserOutcomes(Long userId,Collection<Long> channelIds,String eventType,String title,String content,
            java.util.function.BiConsumer<Long,DeliveryOutcome> observer) {
        java.util.Objects.requireNonNull(observer,"A delivery result observer is required");
        QueryWrapper<NotificationChannel> query = new QueryWrapper<NotificationChannel>().eq("user_id", userId).eq("enabled", 1);
        if (channelIds != null && !channelIds.isEmpty()) query.in("id", channelIds);
        List<NotificationChannel> channels = mapper.selectList(query);
        Map<Long,DeliveryOutcome> outcomes=new LinkedHashMap<>();
        NotificationMessageLocalizer.Message message = localized(userId, eventType, title, content);
        for (NotificationChannel channel : channels) {
            try { send(channel, message.getTitle(), message.getContent()); outcomes.put(channel.getId(),DeliveryOutcome.DELIVERED); }
            catch (RuntimeException error) { outcomes.put(channel.getId(),classify(error));
                // Provider URLs, query tokens, response bodies and message content must not enter logs.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Notification delivery did not complete, channelId={}, outcome={}",channel.getId(),outcomes.get(channel.getId()));
            }
            observer.accept(channel.getId(),outcomes.get(channel.getId()));
        }
        return outcomes;
    }

    public enum GateDecision { ALLOW, SKIP, STOP }
    public interface BeforeAttemptGate {
        GateDecision beforeRecipient(Long userId);
        GateDecision beforeChannel(Long userId,Long channelId);
    }
    /** Skips never masquerade as provider outcomes or as an absence of configured channels. */
    public static final class GuardedBatchResult {
        private final Map<Long,DeliveryOutcome> attemptedOutcomes=new LinkedHashMap<>();
        private final Map<Long,String> skippedChannels=new LinkedHashMap<>();
        private boolean recipientSkipped,noEnabledChannels,stopRemaining;
        public Map<Long,DeliveryOutcome> getAttemptedOutcomes(){return Collections.unmodifiableMap(attemptedOutcomes);}
        public Map<Long,String> getSkippedChannels(){return Collections.unmodifiableMap(skippedChannels);}
        public boolean isRecipientSkipped(){return recipientSkipped;}
        public boolean isNoEnabledChannels(){return noEnabledChannels;}
        public boolean isStopRemaining(){return stopRemaining;}
    }
    /** Tracked appointment callers consume their durable attempt before entering here.
     * Gates and observers are deliberately outside provider-error classification. */
    public GuardedBatchResult notifyUserOutcomesGuarded(Long userId,Collection<Long> channelIds,String eventType,String title,String content,
            BeforeAttemptGate gate,java.util.function.BiConsumer<Long,DeliveryOutcome> observer) {
        Objects.requireNonNull(gate,"A current source gate is required");
        Objects.requireNonNull(observer,"A durable outcome observer is required");
        GuardedBatchResult result=new GuardedBatchResult();
        GateDecision recipient;
        try { recipient=gate.beforeRecipient(userId); }
        catch(RuntimeException unverifiable){recipient=GateDecision.STOP;}
        if(recipient!=GateDecision.ALLOW){result.recipientSkipped=true;result.stopRemaining=recipient!=GateDecision.SKIP;return result;}
        final List<NotificationChannel> channels;
        final NotificationMessageLocalizer.Message message;
        try {
            QueryWrapper<NotificationChannel> query=new QueryWrapper<NotificationChannel>().eq("user_id",userId).eq("enabled",1);
            if(channelIds!=null&&!channelIds.isEmpty())query.in("id",channelIds);
            channels=mapper.selectList(query);
            if(channels.isEmpty()){result.noEnabledChannels=true;return result;}
            message=localized(userId,eventType,title,content);
        }catch(RuntimeException unverifiable){result.stopRemaining=true;return result;}
        for(NotificationChannel candidate:channels) {
            Long channelId=candidate.getId();
            final NotificationChannel current;
            try {
                NotificationChannel before=mapper.selectById(channelId);
                if(!eligible(before,userId)){result.skippedChannels.put(channelId,"CHANNEL_UNAVAILABLE");continue;}
                GateDecision decision=gate.beforeChannel(userId,channelId);
                if(decision!=GateDecision.ALLOW){result.skippedChannels.put(channelId,"CURRENT_GATE_DENIED");if(decision!=GateDecision.SKIP){result.stopRemaining=true;break;}continue;}
                // The gate may have waited. Re-read ownership/configuration for this exact send.
                current=mapper.selectById(channelId);
                if(!eligible(current,userId)){result.skippedChannels.put(channelId,"CHANNEL_UNAVAILABLE");continue;}
            }catch(RuntimeException unverifiable){result.skippedChannels.put(channelId,"CURRENT_GATE_UNAVAILABLE");result.stopRemaining=true;break;}
            DeliveryOutcome outcome;
            try {send(current,message.getTitle(),message.getContent());outcome=DeliveryOutcome.DELIVERED;}
            catch(RuntimeException provider){outcome=classify(provider);}
            result.attemptedOutcomes.put(channelId,outcome);
            try {observer.accept(channelId,outcome);}
            catch(RuntimeException persistenceFailure){result.stopRemaining=true;break;}
        }
        return result;
    }
    private static boolean eligible(NotificationChannel channel,Long userId) {
        return channel!=null&&Objects.equals(channel.getUserId(),userId)&&Integer.valueOf(1).equals(channel.getEnabled());
    }

    private DeliveryOutcome classify(RuntimeException error) {
        if (error instanceof InvalidNotificationConfiguration || error instanceof KnownProviderRejection) return DeliveryOutcome.FAILED;
        if (error instanceof org.springframework.web.client.RestClientResponseException) {
            int status=((org.springframework.web.client.RestClientResponseException)error).getRawStatusCode();
            return status>=400 && status<500 && status!=408 ? DeliveryOutcome.FAILED : DeliveryOutcome.UNKNOWN;
        }
        return DeliveryOutcome.UNKNOWN;
    }

    /** Compatibility API preserves the four outcome values; retryability is an independent fact. */
    public DeliveryOutcome deliverCarePlan(long channelId,String eventKey,String title,String relativePath) {
        return deliverCarePlanAttempt(channelId,eventKey,title,relativePath).getOutcome();
    }

    /** Only an explicit rate-limit rejection without unsupported server timing permits automatic retry. */
    public DeliveryAttempt deliverCarePlanAttempt(long channelId,String eventKey,String title,String relativePath) {
        return deliverWorkflowAttempt(channelId,eventKey,title,relativePath,"CARE_PLAN_",null);
    }

    /** Same safe provider classification; alert workflow does not borrow care-plan enablement. */
    public DeliveryAttempt deliverAlertWorkflowAttempt(long channelId,long recipientId,String eventKey,String title,String relativePath) {
        return deliverWorkflowAttempt(channelId,eventKey,title,relativePath,"ALERT_WORKFLOW_",recipientId);
    }
    public DeliveryAttempt deliverMedicationExecutionAttempt(long channelId,long recipientId,String eventKey,String title,String relativePath) {
        return deliverWorkflowAttempt(channelId,eventKey,title,relativePath,"MEDICATION_EXECUTION_",recipientId);
    }
    public DeliveryAttempt deliverStockWorkflowAttempt(long channelId,long recipientId,String eventKey,String title,String relativePath) {
        return deliverWorkflowAttempt(channelId,eventKey,title,relativePath,"STOCK_WORKFLOW_",recipientId);
    }
    public DeliveryAttempt deliverFamilyAssistanceAttempt(long channelId,long recipientId,String eventKey,String title,String relativePath) {return deliverWorkflowAttempt(channelId,eventKey,title,relativePath,"FAMILY_ASSISTANCE_",recipientId);}
    public DeliveryAttempt deliverEmergencyAssistanceAttempt(long channelId,long recipientId,String eventKey,String title,String relativePath) {return deliverWorkflowAttempt(channelId,eventKey,title,relativePath,"EMERGENCY_ASSISTANCE_",recipientId);}
    private DeliveryAttempt deliverWorkflowAttempt(long channelId,String eventKey,String title,String relativePath,String prefix,Long expectedOwner) {
        try {
            NotificationChannel channel=mapper.selectById(channelId);
            if(channel==null||!Integer.valueOf(1).equals(channel.getEnabled())||(expectedOwner!=null&&!expectedOwner.equals(channel.getUserId())))return new DeliveryAttempt(DeliveryOutcome.NO_CHANNEL,false);
            if(eventKey==null||!eventKey.startsWith(prefix))return new DeliveryAttempt(DeliveryOutcome.FAILED,false);
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

package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.entity.NotificationChannel;
import org.familyhealthcare.mapper.NotificationChannelMapper;
import org.familyhealthcare.util.CurrentUserUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/notification-channel")
public class NotificationChannelController {
    @Autowired private NotificationChannelMapper mapper;
    @Autowired private org.familyhealthcare.service.NotificationDeliveryService delivery;
    @Autowired private org.familyhealthcare.service.RobotChannelConfigService robots;

    @GetMapping("/list")
    public Result<List<NotificationChannel>> list() {
        Long userId = CurrentUserUtil.getCurrentUserId();
        List<NotificationChannel> rows=mapper.selectList(new QueryWrapper<NotificationChannel>().eq("user_id", userId).orderByDesc("updated_at"));
        rows.forEach(row -> {
            row.setWebhookConfigured(row.getWebhookUrl() != null && !row.getWebhookUrl().trim().isEmpty());
            robots.load(row);
            row.setRobotSecretConfigured(row.getRobotSecret() != null && !row.getRobotSecret().trim().isEmpty());
            row.setRobotSecret(null);
        });
        return Result.ok(rows);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/save")
    public Result<String> save(@RequestBody NotificationChannel channel) {
        Long userId = CurrentUserUtil.getCurrentUserId();
        if (channel.getChannelType() == null || channel.getChannelType().trim().isEmpty()) return Result.error(400, "Notificationchanneltypecannot be empty");
        if (channel.getId() != null) {
            NotificationChannel existing = mapper.selectById(channel.getId());
            if (existing == null || !userId.equals(existing.getUserId())) return Result.error(403, "Permission deniedActionsthis Notificationchannel");
            if (channel.getWebhookUrl() == null || channel.getWebhookUrl().trim().isEmpty()) channel.setWebhookUrl(existing.getWebhookUrl());
        } else if (channel.getWebhookUrl() == null || channel.getWebhookUrl().trim().isEmpty()) {
            return Result.error(400, "Webhook Addresscannot be empty");
        }
        if (!java.util.Arrays.asList("DINGTALK_WEBHOOK","WECHAT_WEBHOOK","WEBHOOK").contains(channel.getChannelType())) return Result.error(400,"Unsupported notification channel type.");
        channel.setUserId(userId);
        if (channel.getEnabled() == null) channel.setEnabled(1);
        if (channel.getId() == null) mapper.insert(channel); else mapper.updateById(channel);
        robots.save(channel);
        return Result.ok("Saved successfully");
    }

    @PostMapping("/test/{id}")
    public Result<String> test(@PathVariable Long id) {
        Long userId = CurrentUserUtil.getCurrentUserId();
        NotificationChannel channel = mapper.selectById(id);
        if (channel == null || !userId.equals(channel.getUserId())) return Result.error(403, "Permission deniedActionsthis Notificationchannel");
        String result;
        boolean success = false;
        try {
            delivery.sendForUser(userId, channel, "CHANNEL_TEST", "Chengxin Health notification test", "This is a test message. The notification channel is connected.");
            result = "Test message sent. Check the receiving channel.";
            success = true;
        } catch (Exception e) { result = "Test failed. Check the webhook address, receiver, and network."; }
        channel.setLastTestAt(LocalDateTime.now());
        channel.setLastTestResult(result);
        mapper.updateById(channel);
        return success ? Result.ok(result) : Result.error(400, result);
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        Long userId = CurrentUserUtil.getCurrentUserId();
        NotificationChannel channel = mapper.selectById(id);
        if (channel == null || !userId.equals(channel.getUserId())) return Result.error(403, "Permission deniedActionsthis Notificationchannel");
        mapper.deleteById(id);robots.delete(id);
        return Result.ok("Saved successfully");
    }
}

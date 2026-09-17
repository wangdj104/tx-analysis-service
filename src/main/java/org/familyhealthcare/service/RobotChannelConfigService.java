package org.familyhealthcare.service;
import org.familyhealthcare.entity.NotificationChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class RobotChannelConfigService {
    @Autowired private JdbcTemplate jdbc;
    public void load(NotificationChannel c){List<Map<String,Object>>rows=jdbc.queryForList("SELECT secret,keyword FROM notification_robot_config WHERE channel_id=?",c.getId());if(!rows.isEmpty()){c.setRobotSecret((String)rows.get(0).get("secret"));c.setRobotKeyword((String)rows.get(0).get("keyword"));}}
    public void save(NotificationChannel c){
        if(c.getRobotSecret()==null||c.getRobotSecret().trim().isEmpty()){
            jdbc.update("INSERT INTO notification_robot_config(channel_id,secret,keyword) VALUES(?,NULL,?) ON DUPLICATE KEY UPDATE keyword=VALUES(keyword)",c.getId(),c.getRobotKeyword());
        }else{
            jdbc.update("INSERT INTO notification_robot_config(channel_id,secret,keyword) VALUES(?,?,?) ON DUPLICATE KEY UPDATE secret=VALUES(secret),keyword=VALUES(keyword)",c.getId(),c.getRobotSecret(),c.getRobotKeyword());
        }
    }
    public void delete(Long id){jdbc.update("DELETE FROM notification_robot_config WHERE channel_id=?",id);}
}

package org.familyhealthcare.service;

import org.familyhealthcare.FamilyHealthCareApplication;
import org.familyhealthcare.service.careplan.CarePlanNotificationTransport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.familyhealthcare.entity.NotificationChannel;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

/** Test-classpath-only launcher for actual HTTP/auth/business services on the guarded CI schema. */
public final class CarePlanBrowserApplication {
    public static void main(String[] args) {
        try { launch(); }
        catch(Throwable failure) {
            // Exception messages may include JDBC/configuration credentials. Emit bounded class names only.
            StringBuilder summary=new StringBuilder("CARE_PLAN_BROWSER_BOOT_FAILURE ");
            Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
            for(Throwable current=failure;current!=null&&seen.size()<8&&seen.add(current);current=current.getCause()) {
                if(seen.size()>1)summary.append(" > ");summary.append(current.getClass().getName());
            }
            System.err.println(summary);System.exit(1);
        }
    }
    private static void launch() throws Exception {
        Map<String,String> env=System.getenv();
        for(String flag:Arrays.asList("CARE_PLAN_TEST_ONLY","CARE_PLAN_BROWSER_REQUIRED","CI","GITHUB_ACTIONS"))
            require("true".equals(env.get(flag)),flag);
        String host=env.get("CARE_PLAN_MYSQL_HOST"),port=env.get("CARE_PLAN_MYSQL_PORT"),db=env.get("CARE_PLAN_MYSQL_UPGRADE_DATABASE");
        String user=env.get("CARE_PLAN_MYSQL_USER"),password=env.get("CARE_PLAN_MYSQL_PASSWORD"),loginPassword=env.get("CARE_PLAN_E2E_PASSWORD");
        require(Arrays.asList("127.0.0.1","localhost","::1","mysql").contains(host),"host");
        require(port!=null&&port.matches("[1-9][0-9]{0,4}")&&Integer.parseInt(port)<=65535,"port");
        require(db!=null&&db.matches("care_plan_test_[a-z0-9_]+")&&db.length()<=64,"database");
        require(user!=null&&user.matches("care_plan_test_[a-z0-9_]+")&&user.length()<=32,"user");
        require(password!=null&&password.matches("[a-zA-Z0-9]{24,128}"),"generated database password");
        require(loginPassword!=null&&loginPassword.matches("[a-zA-Z0-9]{24,128}"),"generated login password");
        String url="jdbc:mysql://"+("::1".equals(host)?"[::1]":host)+":"+port+"/"+db+"?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try(Connection connection=DriverManager.getConnection(url,user,password)) {
            require(connection.getMetaData().getDatabaseProductName().equals("MySQL"),"native MySQL");
            try(java.sql.Statement session=connection.createStatement()){session.execute("SET SESSION time_zone='+00:00'");}
            try(java.sql.Statement s=connection.createStatement();java.sql.ResultSet r=s.executeQuery("SELECT VERSION(),DATABASE(),@@server_uuid")){
                require(r.next()&&r.getString(1).startsWith("8.0.")&&db.equals(r.getString(2))&&r.getString(3).equals(env.get("CARE_PLAN_BROWSER_SERVER_UUID")),"native exact disposable service/schema");
            }
            ScriptUtils.executeSqlScript(connection,new EncodedResource(new ClassPathResource("sql/care-plan-e2e-fixture.sql"),StandardCharsets.UTF_8));
            String hash=new BCryptPasswordEncoder().encode(loginPassword);
            try(java.sql.PreparedStatement s=connection.prepareStatement("UPDATE sys_user SET password=? WHERE id BETWEEN 9001 AND 9006")){
                s.setString(1,hash);require(s.executeUpdate()==6,"synthetic account count");
            }
        }
        Map<String,Object> config=new HashMap<>();
        config.put("spring.profiles.active","dev");config.put("spring.datasource.url",url);
        config.put("spring.datasource.username",user);config.put("spring.datasource.password",password);
        config.put("spring.datasource.hikari.connection-init-sql","SET SESSION time_zone='+00:00'");
        config.put("server.address","127.0.0.1");config.put("server.port",18081);
        config.put("jwt.secret",UUID.randomUUID().toString()+UUID.randomUUID());
        config.put("care-plan.enabled",true);config.put("app.bootstrap-admin.enabled",false);
        config.put("deepseek.api.key","");config.put("deepseek.api.url","http://127.0.0.1:9/disabled");
        config.put("bailian.api-key","");config.put("ocr.vision.api-key","");
        config.put("ocr.vision.base-url","http://127.0.0.1:9/disabled");config.put("langflow.enabled",false);
        config.put("audit.capture-client-ip",false);config.put("springfox.documentation.enabled",false);
        SpringApplication application=new SpringApplication(FamilyHealthCareApplication.class,SyntheticExternalServices.class);
        application.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("guarded-browser-test-only",config)));
        application.run();
    }
    private static void require(boolean value,String label){if(!value)throw new IllegalStateException("care-plan browser launcher guard: "+label);}

    @TestConfiguration public static class SyntheticExternalServices {
        // This disables only background external/AI/reminder work in a test-classpath launcher.
        // All HTTP/auth/care-plan/persistence services remain their actual production beans.
        @Bean static BeanFactoryPostProcessor disableBackgroundSchedulers(){return factory->{
            BeanDefinitionRegistry registry=(BeanDefinitionRegistry)factory;
            String name=TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
            if(registry.containsBeanDefinition(name))registry.removeBeanDefinition(name);
        };}
        @Bean @Primary CarePlanNotificationTransport syntheticCarePlanTransport(){return (channel,event,title,path)->{
            require("Care plan update".equals(title),"generic provider body");
            require(path.matches("/care-plans/[1-9][0-9]*"),"authenticated relative path");
            // Synthetic uncertainty deliberately never claims a successful external delivery.
            return CarePlanNotificationTransport.DeliveryOutcome.UNKNOWN;
        };}
        @Bean @Primary NotificationDeliveryService blockLegacyExternalDelivery(){return new NotificationDeliveryService(){
            @Override public void send(NotificationChannel channel,String title,String content){throw new AssertionError("Legacy external notification is disabled in browser acceptance");}
            @Override public void sendForUser(Long user,NotificationChannel channel,String event,String title,String content){throw new AssertionError("Legacy external notification is disabled in browser acceptance");}
            @Override public boolean notifyUser(Long user,String title,String content){return false;}
            @Override public boolean notifyUser(Long user,String event,String title,String content){return false;}
            @Override public boolean notifyUser(Long user,Collection<Long> channels,String title,String content){return false;}
            @Override public boolean notifyUser(Long user,Collection<Long> channels,String event,String title,String content){return false;}
            @Override public CarePlanNotificationTransport.DeliveryOutcome deliverCarePlan(long channel,String event,String title,String path){return CarePlanNotificationTransport.DeliveryOutcome.UNKNOWN;}
            @Override public CarePlanNotificationTransport.DeliveryAttempt deliverCarePlanAttempt(long channel,String event,String title,String path){return new CarePlanNotificationTransport.DeliveryAttempt(CarePlanNotificationTransport.DeliveryOutcome.UNKNOWN,false);}
        };}
    }
}

package org.familyhealthcare.service;

import org.familyhealthcare.config.WebMvcConfig;
import org.familyhealthcare.controller.*;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.interceptor.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.impl.SysUserServiceImpl;
import org.familyhealthcare.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual MVC, real JWT, real password mutation service; only storage/provider seams are synthetic. */
class SecurityBoundaryMvcTest {
    @Configuration @EnableWebMvc static class Config { }
    @RestController static class AdjacentController { @GetMapping({"/api/ai-adjacent","/api/health-report/generate-adjacent","/api/data-export/csv-adjacent","/api/care/backup-adjacent"}) String adjacent(){return "adjacent";} }
    GenericWebApplicationContext context; MockMvc mvc; JwtUtil jwt; SysUserServiceImpl users;
    SysMenuMapper menus; HealthReportService reports; DataExportService exports; FamilyBackupService backup;
    org.familyhealthcare.support.PatientAuthorizationFixture patientFixture; AiAnalysisRecordMapper aiRecords;
    NotificationChannelMapper channels; WebhookTransport hookTransport;
    SysUser current; String password="SyntheticStrong!123"; List<SysMenu> grants=new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        current=new SysUser();current.setId(7L);current.setUsername("synthetic");current.setStatus(1);current.setDeleted(0);
        current.setPassword(new BCryptPasswordEncoder().encode(password));
        SysUserMapper mapper=mock(SysUserMapper.class);
        when(mapper.selectById(any())).thenAnswer(a->copy());when(mapper.selectOne(any())).thenAnswer(a->copy());
        when(mapper.updateById(any())).thenAnswer(a->{SysUser update=a.getArgument(0);if(update.getPassword()!=null)current.setPassword(update.getPassword());return 1;});
        jwt=new JwtUtil();ReflectionTestUtils.setField(jwt,"secret","synthetic-server-secret-key-for-security-regressions-123456789");ReflectionTestUtils.setField(jwt,"expiration",3600000L);
        users=new SysUserServiceImpl();ReflectionTestUtils.setField(users,"sysUserMapper",mapper);ReflectionTestUtils.setField(users,"jwtUtil",jwt);
        menus=mock(SysMenuMapper.class);when(menus.selectMenusByUserId(anyLong())).thenAnswer(a->grants);
        SysRoleMapper roles=mock(SysRoleMapper.class);when(roles.selectRolesByUserId(anyLong())).thenReturn(Collections.emptyList());
        context=new GenericWebApplicationContext();context.setServletContext(new MockServletContext());
        bean(JwtUtil.class,jwt);bean(SysUserMapper.class,mapper);bean(SysRoleMapper.class,roles);bean(SysMenuMapper.class,menus);
        bean(SysUserService.class,users);bean(SysRoleService.class,mock(SysRoleService.class));bean(SysMenuService.class,mock(SysMenuService.class));
        bean(UserLanguagePreferenceService.class,mock(UserLanguagePreferenceService.class));bean(OperationAuditLogMapper.class,mock(OperationAuditLogMapper.class));
        patientFixture=new org.familyhealthcare.support.PatientAuthorizationFixture();
        aiRecords=mock(AiAnalysisRecordMapper.class);when(aiRecords.selectList(any())).thenReturn(Collections.emptyList());when(aiRecords.insert(any())).thenReturn(1);
        org.familyhealthcare.service.impl.AiAnalysisServiceImpl ai=new org.familyhealthcare.service.impl.AiAnalysisServiceImpl();
        ReflectionTestUtils.setField(ai,"dataScopeHelper",patientFixture.scope);ReflectionTestUtils.setField(ai,"aiAnalysisRecordMapper",aiRecords);bean(AiAnalysisService.class,ai);
        reports=mock(HealthReportService.class);exports=mock(DataExportService.class);backup=mock(FamilyBackupService.class);
        bean(HealthReportService.class,reports);bean(DataExportService.class,exports);bean(FamilyBackupService.class,backup);
        channels=mock(NotificationChannelMapper.class);hookTransport=mock(WebhookTransport.class);
        bean(NotificationChannelMapper.class,channels);bean(RobotChannelConfigService.class,mock(RobotChannelConfigService.class));
        bean(NotificationDeliveryService.class,new NotificationDeliveryService(new WebhookTargetPolicy(host->{
            if("synthetic.test".equals(host))return new java.net.InetAddress[]{java.net.InetAddress.getByAddress(new byte[]{93,(byte)184,(byte)216,34})};
            return new java.net.InetAddress[]{java.net.InetAddress.getLoopbackAddress()};
        }),hookTransport));
        new AnnotatedBeanDefinitionReader(context).register(Config.class,WebMvcConfig.class,JwtInterceptor.class,PermissionInterceptor.class,AuditLogInterceptor.class,AuthController.class,AiAnalysisController.class,HealthReportController.class,DataExportController.class,FamilyBackupController.class,NotificationChannelController.class,AdjacentController.class);
        context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBeansOfType(javax.servlet.Filter.class).values().toArray(new javax.servlet.Filter[0])).build();
    }
    <T> void bean(Class<T> type,T value){context.getBeanFactory().registerSingleton(type.getName(),value);}
    SysUser copy(){SysUser u=new SysUser();u.setId(current.getId());u.setUsername(current.getUsername());u.setPassword(current.getPassword());u.setStatus(current.getStatus());u.setDeleted(current.getDeleted());return u;}
    @AfterEach void close(){context.close();if(patientFixture!=null)patientFixture.close();}
    void grant(String permission){SysMenu m=new SysMenu();m.setPermission(permission);grants.add(m);}
    String login(){return users.login("synthetic",password);}
    @Test void actualAiRoutesRequireTheExistingMenuPermissionIncludingMatrixParameters() throws Exception {
        String token=login();
        for(String path:Arrays.asList("/api/ai/history","/api/ai;test=1/history","/api/ai/history;test=1"))
            mvc.perform(get(path).param("patientId","1").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai-adjacent").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        grant("dialysis:ai:view");
        mvc.perform(get("/api/ai/history").param("patientId","1").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/ai-adjacent").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void authInfoRequiresLiveAccountAndCredentialAfterEachPasswordMutation() throws Exception {
        for(int mutation=0;mutation<3;mutation++){
            String token=login();String oldHash=current.getPassword();String next="SyntheticNext!"+mutation+"234";
            if(mutation==0)users.changePassword(7L,password,next);
            else if(mutation==1)users.resetPassword(7L,next);
            else {SysUser update=new SysUser();update.setId(7L);update.setPassword(next);users.updateUser(update);}
            assertNotEquals(oldHash,current.getPassword());password=next;
            mvc.perform(get("/api/auth/info").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/ai/history").param("patientId","1").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/auth/info").header("Authorization","Bearer "+login())).andExpect(status().isOk());
        }
        String fresh=login();current.setStatus(0);
        mvc.perform(get("/api/auth/info").header("Authorization","Bearer "+fresh)).andExpect(status().isUnauthorized());
        current.setStatus(1);current.setDeleted(1);
        mvc.perform(get("/api/auth/info").header("Authorization","Bearer "+fresh)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/info")).andExpect(status().isUnauthorized());
    }
    @Test void menuPermissionDoesNotExpandPatientScopeOnTheActualAiDeleteRoute()throws Exception {
        grant("dialysis:ai:view");String token=login();AiAnalysisRecord foreign=new AiAnalysisRecord();foreign.setId(101L);foreign.setPatientId(2L);foreign.setUserId(9L);when(aiRecords.selectById(101L)).thenReturn(foreign);
        mvc.perform(delete("/api/ai/delete/101").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.code").value(403));verify(aiRecords,never()).deleteById(any(java.io.Serializable.class));
        AiAnalysisRecord owned=new AiAnalysisRecord();owned.setId(102L);owned.setPatientId(1L);owned.setUserId(7L);when(aiRecords.selectById(102L)).thenReturn(owned);when(aiRecords.deleteById(102L)).thenReturn(1);
        mvc.perform(delete("/api/ai/delete/102").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.code").value(200));verify(aiRecords).deleteById(102L);
    }
    @Test void loginRouteIssuesCredentialBoundTokenAndLegacyTokensAreRejected()throws Exception {
        String body="{\"username\":\"synthetic\",\"password\":\""+password+"\"}";
        String json=mvc.perform(post("/api/auth/login").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("data").get("token").asText();
        assertTrue(jwt.matchesCredential(token,7L,current.getPassword()));
        String payload=new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(payload.contains(password));assertFalse(payload.contains(current.getPassword()));assertTrue(payload.contains("credentialStamp"));
        String legacy=jwt.generateToken(7L,"synthetic");
        mvc.perform(get("/api/auth/info").header("Authorization","Bearer "+legacy)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/info;synthetic=1").header("Authorization","Bearer "+legacy)).andExpect(status().isUnauthorized());
    }
    @Test void channelSaveValidatesNewAndRetainedUrlsBeforeAnyDatabaseWrite()throws Exception {
        String token=login();
        for(String url:Arrays.asList("http://127.0.0.1/hook","https://127.0.0.1/hook","https://internal.test/hook")){
            mvc.perform(post("/api/notification-channel/save").header("Authorization","Bearer "+token).contentType("application/json").content("{\"channelType\":\"WEBHOOK\",\"webhookUrl\":\""+url+"\"}")).andExpect(jsonPath("$.code").value(400));
        }
        verify(channels,never()).insert(any());verify(channels,never()).updateById(any());verifyNoInteractions(hookTransport);
        NotificationChannel old=new NotificationChannel();old.setId(1L);old.setUserId(7L);old.setWebhookUrl("https://internal.test/hook");when(channels.selectById(1L)).thenReturn(old);
        mvc.perform(post("/api/notification-channel/save").header("Authorization","Bearer "+token).contentType("application/json").content("{\"id\":1,\"channelType\":\"WEBHOOK\"}")).andExpect(jsonPath("$.code").value(400));
        verify(channels,never()).updateById(any());
        mvc.perform(post("/api/notification-channel/save").header("Authorization","Bearer "+token).contentType("application/json").content("{\"channelType\":\"WEBHOOK\",\"webhookUrl\":\"https://synthetic.test/hook\"}")).andExpect(jsonPath("$.code").value(200));
        verify(channels).insert(argThat(c->Long.valueOf(7L).equals(c.getUserId())));verifyNoInteractions(hookTransport);
    }
    @Test void sensitiveLegacyExportsArePrivateBeforeAuthenticationAndOn400403500() throws Exception {
        String token=login();grant("health-report:view");grant("data-export:view");
        for(String path:Arrays.asList("/api/health-report/generate","/api/data-export/csv")){
            mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store, private")).andExpect(header().string("X-Content-Type-Options","nosniff"));
            mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store, private"));
            mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content("{broken")).andExpect(status().isBadRequest()).andExpect(header().string("X-Content-Type-Options","nosniff"));
        }
        when(reports.generateReport(any(),anyString())).thenReturn(new byte[]{1});when(exports.exportCsv(any())).thenReturn(new byte[]{1});when(backup.exportArchive()).thenReturn(new byte[]{1});
        mvc.perform(post("/api/health-report/generate").header("Authorization","Bearer "+token).contentType("application/json").content("{\"patientId\":1}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"));
        mvc.perform(post("/api/data-export/csv").header("Authorization","Bearer "+token).contentType("application/json").content("{\"patientId\":1}")).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get("/api/care/backup").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"));
        mvc.perform(get("/api/care/backup")).andExpect(status().isUnauthorized()).andExpect(header().string("X-Content-Type-Options","nosniff"));
        when(reports.generateReport(any(),anyString())).thenThrow(new IllegalStateException("synthetic denied"));
        mvc.perform(post("/api/health-report/generate").header("Authorization","Bearer "+token).contentType("application/json").content("{\"patientId\":1}")).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store, private"));
        doThrow(new RuntimeException("synthetic error")).when(reports).generateReport(any(),anyString());
        mvc.perform(post("/api/health-report/generate").header("Authorization","Bearer "+token).contentType("application/json").content("{\"patientId\":1}")).andExpect(status().isInternalServerError()).andExpect(header().string("X-Content-Type-Options","nosniff"));
        grants.clear();
        mvc.perform(post("/api/data-export/csv").header("Authorization","Bearer "+token).contentType("application/json").content("{\"patientId\":1}")).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store, private"));
    }
    @Test void mappingRejectionsRetainPrivateHeadersOnExactSensitiveRoutesOnly()throws Exception {
        for(String path:Arrays.asList("/api/health-report/generate","/api/data-export/csv","/api/health-report/generate/","/api/data-export;synthetic=1/csv")){
            mvc.perform(get(path)).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Cache-Control","no-store, private")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        }
        for(String path:Arrays.asList("/api/care/backup","/api/care/backup/preview","/api/care/backup/restore"))
            mvc.perform(put(path)).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Cache-Control","no-store, private")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        String token=login();grant("health-report:view");grant("data-export:view");
        for(String path:Arrays.asList("/api/health-report/generate-adjacent","/api/data-export/csv-adjacent","/api/care/backup-adjacent"))
            mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().doesNotExist("Cache-Control")).andExpect(header().doesNotExist("X-Content-Type-Options"));
    }
}

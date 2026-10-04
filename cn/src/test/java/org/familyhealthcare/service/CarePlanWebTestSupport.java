package org.familyhealthcare.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.familyhealthcare.config.WebMvcConfig;
import org.familyhealthcare.controller.CarePlanExceptionAdvice;
import org.familyhealthcare.entity.*;
import org.familyhealthcare.interceptor.*;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.careplan.*;
import org.familyhealthcare.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.*;
import java.util.*;
import static org.familyhealthcare.service.CarePlanTestFixture.*;
import static org.familyhealthcare.service.CarePlanLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Shared actual application MVC/JWT/permission/audit chain and synthetic JDBC fixture. */
abstract class CarePlanWebTestSupport {
    @Configuration @EnableWebMvc @EnableTransactionManagement static class WebConfig { }
    CarePlanLifecycleTest h;
    GenericWebApplicationContext context;
    MockMvc mvc;
    ObjectMapper json=new ObjectMapper();
    JwtUtil jwt;
    CarePlanNotificationTransport transport;
    @BeforeEach void setup() throws Exception {
        h=new CarePlanLifecycleTest();h.setup();
        // The shared H2 fixture skips the legacy migration's conditional audit-column DDL.
        for(String ddl:new String[]{"action_type VARCHAR(30)","target_type VARCHAR(80)","target_id VARCHAR(80)","detail_json CLOB"})
            h.f.jdbc().execute("ALTER TABLE operation_audit_log ADD COLUMN IF NOT EXISTS "+ddl);
        buildContext(true);
    }
    void buildContext(boolean enabled) throws Exception {
        ReflectionTestUtils.setField(h.properties,"enabled",enabled);
        context=new GenericWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("care",Collections.singletonMap("care-plan.enabled",enabled)));
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,()->h.f.jdbc());
        context.registerBean(CarePlanProperties.class,()->h.properties);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(h.f.jdbc().getDataSource()));
        jwt=mock(JwtUtil.class);when(jwt.validateToken(anyString())).thenAnswer(a->a.getArgument(0).toString().matches("synthetic-[0-9]+"));
        when(jwt.getUserIdFromToken(anyString())).thenAnswer(a->Long.parseLong(a.getArgument(0).toString().substring(10)));
        context.registerBean(JwtUtil.class,()->jwt);
        SysUserMapper users=mock(SysUserMapper.class);when(users.selectById(any())).thenAnswer(a->{long id=Long.parseLong(a.getArgument(0).toString());
            List<SysUser> rows=h.f.jdbc().query("SELECT id,username,status,deleted FROM sys_user WHERE id=?",(r,i)->{SysUser u=new SysUser();u.setId(r.getLong("id"));u.setUsername(r.getString("username"));u.setStatus(r.getInt("status"));u.setDeleted(r.getInt("deleted"));return u;},id);return rows.isEmpty()?null:rows.get(0);});
        context.registerBean(SysUserMapper.class,()->users);
        SysRoleMapper roles=mock(SysRoleMapper.class);when(roles.selectRolesByUserId(anyLong())).thenAnswer(a->h.f.jdbc().query("SELECT r.role_code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status=1 AND r.deleted=0",(r,i)->{SysRole role=new SysRole();role.setRoleCode(r.getString(1));return role;},(Object)a.getArgument(0)));
        context.registerBean(SysRoleMapper.class,()->roles);
        context.registerBean(SysMenuMapper.class,()->mock(SysMenuMapper.class));
        context.registerBean(OperationAuditLogMapper.class,()->mock(OperationAuditLogMapper.class));
        transport=mock(CarePlanNotificationTransport.class);context.registerBean(CarePlanNotificationTransport.class,()->transport);
        AnnotatedBeanDefinitionReader reader=new AnnotatedBeanDefinitionReader(context);
        reader.register(WebConfig.class,WebMvcConfig.class,JwtInterceptor.class,PermissionInterceptor.class,AuditLogInterceptor.class,CarePlanExceptionAdvice.class,
                CarePlanAuthorizationService.class,CarePlanQueryService.class,CarePlanCommandStore.class,CarePlanEventStore.class,CarePlanNotificationWorker.class,CarePlanService.class,CarePlanActionService.class,CareNurseAssignmentService.class,
                org.familyhealthcare.controller.CareNurseAssignmentController.class);
        Class<?> controller=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.controller.CarePlanController"));
        Class<?> projector=assertDoesNotThrow(()->Class.forName("org.familyhealthcare.service.careplan.CarePlanTimelineProjector"));
        reader.register(controller,projector);
        context.registerBean(ObjectMapper.class,()->new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder().build());
        registerReportBeans(reader);
        context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void close() throws Exception {if(context!=null)context.close();if(h!=null)h.close();}

    ResultActions perform(MockHttpServletRequestBuilder req,long actor)throws Exception{return mvc.perform(req.header("Authorization","Bearer synthetic-"+actor));}
    MockHttpServletRequestBuilder command(String path,Map<String,Object>body,long version)throws Exception {Map<String,Object>b=new LinkedHashMap<>(body);b.put("commandKey",key());b.put("expectedVersion",version);return post(path).contentType("application/json").content(json.writeValueAsString(b));}
    @SuppressWarnings("unchecked") Map<String,Object>read(ResultActions result)throws Exception{return json.readValue(result.andReturn().getResponse().getContentAsString(),Map.class);}
    @SuppressWarnings("unchecked") Map<String,Object>create()throws Exception{return (Map<String,Object>)read(perform(command("/api/care-plans",body(1),0),DOCTOR).andExpect(status().isOk())).get("data");}
    void registerReportBeans(AnnotatedBeanDefinitionReader reader) {
        reader.register(org.familyhealthcare.controller.CareExecutionReportController.class,CareExecutionReportService.class,
                CareExecutionReportAccess.class,CareExecutionReportProjector.class,CareExecutionReportHtmlRenderer.class,CareExecutionReportCsvRenderer.class);
        if(h.properties.isEnabled())context.registerBean(CareExecutionReportRenderer.class,this::reportRenderer);
    }
    CareExecutionReportRenderer reportRenderer(){return new CareExecutionReportRenderer(new CareExecutionReportHtmlRenderer(),new CareExecutionReportCsvRenderer(),"");}
}

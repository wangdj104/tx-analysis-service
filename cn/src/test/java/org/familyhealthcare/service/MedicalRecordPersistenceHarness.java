package org.familyhealthcare.service;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.plugin.Interceptor;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.familyhealthcare.mapper.*;
import org.familyhealthcare.service.impl.MedicalRecordServiceImpl;
import org.familyhealthcare.util.DataScopeHelper;
import org.springframework.context.annotation.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import static org.mockito.Mockito.mock;

/** Small actual mapper/transaction context for H2 and guarded native MySQL synthetic tests. */
final class MedicalRecordPersistenceHarness {
    @Configuration @EnableTransactionManagement static class TxConfig { }
    static GenericApplicationContext create(DataSource dataSource,Interceptor... plugins) throws Exception {
        GenericApplicationContext context=new GenericApplicationContext();
        context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(dataSource));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(dataSource));
        MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(dataSource);factory.setPlugins(plugins);SqlSessionFactory sql=factory.getObject();
        mapper(context,MedicalRecordMapper.class,sql);mapper(context,MedicalRecordItemMapper.class,sql);mapper(context,MedicalRecordAttachmentMapper.class,sql);mapper(context,PatientMapper.class,sql);
        // Name resolution and unused membership collaborator have no persistence/transaction side effects.
        context.registerBean(HealthIndicatorService.class,()->mock(HealthIndicatorService.class));
        context.registerBean(CareMembershipService.class,()->mock(CareMembershipService.class));
        new AnnotatedBeanDefinitionReader(context).register(TxConfig.class,DataScopeHelper.class,MedicalRecordServiceImpl.class,DoctorWorkspaceService.class,MedicalRecordReviewAccess.class);
        context.refresh();return context;
    }
    private static <T> void mapper(GenericApplicationContext context,Class<T> type,SqlSessionFactory sql) throws Exception {MapperFactoryBean<T> factory=new MapperFactoryBean<>(type);factory.setSqlSessionFactory(sql);factory.afterPropertiesSet();T value=factory.getObject();context.registerBean(type,()->value);}
}

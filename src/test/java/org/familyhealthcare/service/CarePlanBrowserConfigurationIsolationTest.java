package org.familyhealthcare.service;

import org.familyhealthcare.FamilyHealthCareApplication;
import org.familyhealthcare.controller.CarePlanController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 执行真实浏览器启动器的配置阶段，不连接数据库，也不启动 HTTP 服务。 */
class CarePlanBrowserConfigurationIsolationTest {
    @Test void ordinaryBrowserApplicationExcludesUnrelatedTestFixtures() {
        Map<String,Object> properties = new LinkedHashMap<>();
        properties.put("care-plan.enabled", true);
        SpringApplication application = CarePlanBrowserApplication.createApplication(properties);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        Map<String,BeanDefinition> definitions = new LinkedHashMap<>();
        application.addInitializers(context -> context.addBeanFactoryPostProcessor(factory -> {
            assertFalse(((DefaultListableBeanFactory) factory).isAllowBeanDefinitionOverriding(),
                    "Browser acceptance must keep duplicate bean definitions fail-closed");
            for (String name : factory.getBeanDefinitionNames()) {
                definitions.put(name, factory.getBeanDefinition(name));
            }
            // ConfigurationClassPostProcessor 已完成真实组件扫描；在创建单例、初始化数据源、
            // 启动调度任务、运行器和 HTTP 服务之前终止，避免产生任何外部副作用。
            throw new DefinitionInspectionComplete();
        }));

        assertThrows(DefinitionInspectionComplete.class,
                () -> application.run("--spring.config.location=optional:classpath:/browser-isolation-no-config.yml"),
                "The real launcher must finish parsing configuration without bean overrides");
        assertTrue(definitions.values().stream().anyMatch(definition ->
                FamilyHealthCareApplication.class.getName().equals(beanClassName(definition))));
        assertTrue(definitions.values().stream().anyMatch(definition ->
                CarePlanController.class.getName().equals(beanClassName(definition))));
        assertTrue(definitions.containsKey("syntheticCarePlanTransport"),
                "The deliberately supplied browser-only configuration must remain available");
        assertTrue(definitions.containsKey("blockLegacyExternalDelivery"));
        for (Class<?> fixture : Arrays.asList(
                ClinicalImportDialysisPersistenceTest.H2Database.class,
                ClinicalImportDialysisPersistenceTest.Config.class,
                MedicalRecordIntegrityTest.TxConfig.class,
                MedicalRecordPersistenceHarness.TxConfig.class,
                SecurityBoundaryMvcTest.Config.class,
                SecurityBoundaryMvcTest.AdjacentController.class,
                CarePlanWebTestSupport.WebConfig.class,
                CarePlanDraftTest.TransactionConfiguration.class,
                CarePlanMysqlIntegrationTest.MysqlReplayTransactionConfiguration.class)) {
            assertFalse(definitions.values().stream().anyMatch(definition ->
                    fixture.getName().equals(beanClassName(definition))), fixture.getName());
        }
        assertFalse(definitions.containsKey("scope"), "The import fixture's mock scope must not replace production authorization");
        assertFalse(definitions.containsKey("jdbc"), "The import fixture must not replace the real datasource wiring");
    }

    @Test void explicitlyImportedPersistenceFixtureStillUsesRealTransactions() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ClinicalImportDialysisPersistenceTest.H2Database.class)) {
            assertNotNull(context.getBean(ClinicalImportDialysisPersistenceTest.Config.class));
            assertTrue(AopUtils.isAopProxy(context.getBean(ClinicalImportService.class)));
            assertTrue(AopUtils.isAopProxy(context.getBean(MedicalRecordService.class)));
            assertTrue(AopUtils.isAopProxy(context.getBean(DialysisRecordService.class)));
            new TransactionTemplate(context.getBean(ClinicalImportDialysisPersistenceTest.FaultingTransactionManager.class))
                    .execute(status -> {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        return null;
                    });
        }
    }

    private static String beanClassName(BeanDefinition definition) {
        return definition instanceof AnnotatedBeanDefinition
                ? ((AnnotatedBeanDefinition) definition).getMetadata().getClassName()
                : definition.getBeanClassName();
    }

    private static final class DefinitionInspectionComplete extends RuntimeException { }
}

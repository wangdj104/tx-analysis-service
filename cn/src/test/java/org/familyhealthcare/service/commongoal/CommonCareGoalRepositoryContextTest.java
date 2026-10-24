package org.familyhealthcare.service.commongoal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reproduces the repository proxy creation performed during application startup. */
class CommonCareGoalRepositoryContextTest {
    @ParameterizedTest
    @ValueSource(classes = {CommonCareGoalRepository.class, CommonCareGoalReadRepository.class})
    void repositoriesStartWithClassBasedExceptionTranslation(Class<?> repositoryType) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
                PersistenceExceptionTranslationPostProcessor processor =
                        new PersistenceExceptionTranslationPostProcessor();
                processor.setProxyTargetClass(true);
                return processor;
            });
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(
                    new DriverManagerDataSource("jdbc:h2:mem:common_goal_proxy", "sa", "")));
            context.register(repositoryType);

            context.refresh();

            assertTrue(AopUtils.isCglibProxy(context.getBean(repositoryType)),
                    "Repository must remain injectable by its concrete type with exception translation enabled");
        }
    }
}

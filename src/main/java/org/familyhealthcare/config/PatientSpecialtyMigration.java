package org.familyhealthcare.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/** Applies the additive, repeatable specialty-role upgrade before the service reports ready. */
@Component
public class PatientSpecialtyMigration implements ApplicationRunner {
    private final DataSource dataSource;

    public PatientSpecialtyMigration(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        }
        // 这份脚本只适用于 MySQL/MariaDB，每次启动都会执行；它补角色和菜单关系，不替患者推断专病分配。
        if (!"MySQL".equalsIgnoreCase(product) && !"MariaDB".equalsIgnoreCase(product)) return;
        new ResourceDatabasePopulator(new ClassPathResource("sql/patient_specialty_roles_20260923.sql"))
                .execute(dataSource);
    }
}

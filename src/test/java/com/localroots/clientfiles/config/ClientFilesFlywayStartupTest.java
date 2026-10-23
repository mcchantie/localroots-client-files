package com.localroots.clientfiles.config;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ClientFilesFlywayStartupTest {
    @Test void startupUsesSeparateHistoryAndDoesNotChangeCrmHistory() {
        JdbcDataSource db=new JdbcDataSource();
        db.setURL("jdbc:h2:mem:cf-flyway-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(db);
        jdbc.execute("create table flyway_schema_history (version varchar(50))");
        jdbc.update("insert into flyway_schema_history(version) values('104')");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withBean(DataSource.class,()->db)
                .withPropertyValues("spring.flyway.enabled=true", "spring.flyway.table=client_files_flyway_schema_history",
                        "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=99",
                        "spring.flyway.locations=classpath:db/flyway-startup-fixture")
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(Flyway.class);
                    assertThat(context.getBean(Flyway.class).info().current().getVersion().toString()).isEqualTo("100");
                    assertThat(jdbc.queryForObject("select count(*) from client_files_migration_fixture",Integer.class)).isZero();
                    assertThat(jdbc.queryForObject("select version from flyway_schema_history",String.class)).isEqualTo("104");
                });
    }
}

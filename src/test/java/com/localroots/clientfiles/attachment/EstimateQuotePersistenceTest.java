package com.localroots.clientfiles.attachment;

import com.localroots.clientfiles.contact.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses real JPA and JDBC in one transaction, including the V4 foreign keys. */
class EstimateQuotePersistenceTest {
    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses=ContactRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            var db=new JdbcDataSource();
            db.setURL("jdbc:h2:mem:quote-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            return db;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource db) {
            var factory=new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(db);
            factory.setPackagesToScan("com.localroots.clientfiles.contact");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {return new JpaTransactionManager(factory);}
        @Bean ContactService contacts(ContactRepository repository) {return new ContactService(repository);}
    }
    @Test void newContactIsVisibleToJdbcForeignKeyAndRetryReusesIt() {exercise(false);}
    @Test void flushedContactAndMappingStillRollBackTogether() {exercise(true);}
    void exercise(boolean rollback) {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var db=context.getBean(DataSource.class);var jdbc=new JdbcTemplate(db);
            jdbc.execute("create table contact_attachments(id uuid primary key)");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V4__estimator_quote_contact_links.sql")).execute(db);
            var tenant=UUID.randomUUID();var id=UUID.randomUUID();var estimate=UUID.randomUUID();
            jdbc.update("insert into contact_attachments(id) values(?)",id);
            var attachment=AttachmentEntity.pending(id,tenant,null,estimate,null,AttachmentCategory.ESTIMATES,AttachmentFileKind.DOCUMENT,
                "estimate.txt","Estimate","text/plain",10,null,"bucket","original-key","ESTIMATOR",null,null,null);
            attachment.markReady(10L,null);
            var repo=mock(AttachmentRepository.class);
            when(repo.lockForEstimateQuote(id,tenant)).thenReturn(Optional.of(attachment));
            var service=new EstimateQuoteAssignmentService(repo,context.getBean(ContactService.class),jdbc);
            var transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status->{
                var request=new EstimateQuoteAssignmentService.Request(estimate,"Mary Ann","De La Cruz",null,null);
                var contact=service.assign(tenant,id,request).contactId();
                assertEquals(contact,jdbc.queryForObject("select contact_id from estimator_quote_contact_links",UUID.class));
                assertEquals(1,jdbc.queryForObject("select count(*) from contacts where id=?",Integer.class,contact));
                assertEquals(contact,service.assign(tenant,id,request).contactId());
                assertEquals(1,jdbc.queryForObject("select count(*) from contacts",Integer.class));
                if(rollback) status.setRollbackOnly();
            });
            assertEquals(rollback?0:1,jdbc.queryForObject("select count(*) from contacts",Integer.class));
            assertEquals(rollback?0:1,jdbc.queryForObject("select count(*) from estimator_quote_contact_links",Integer.class));
        }
    }
}

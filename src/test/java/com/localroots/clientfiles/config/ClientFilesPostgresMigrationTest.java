package com.localroots.clientfiles.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Runs in CI against PostgreSQL, using an isolated random schema for each case. */
@EnabledIfEnvironmentVariable(named="CLIENT_FILES_MIGRATION_TEST_URL", matches=".+")
class ClientFilesPostgresMigrationTest {
    @Test void freshSchemaMigratesAndRepeatedStartupIsNoOp() throws Exception { verify(false); }
    @Test void adoptsManuallyAppliedSchemaAndPreservesFilesAndCrmHistory() throws Exception { verify(true); }

    private void verify(boolean legacy) throws Exception {
        String url=System.getenv("CLIENT_FILES_MIGRATION_TEST_URL");
        String user=System.getenv().getOrDefault("CLIENT_FILES_MIGRATION_TEST_USER","postgres");
        String password=System.getenv().getOrDefault("CLIENT_FILES_MIGRATION_TEST_PASSWORD","postgres");
        String schema="cf_test_"+UUID.randomUUID().toString().replace("-","");
        UUID tenant=UUID.randomUUID(),contact=UUID.randomUUID(),file=UUID.randomUUID();
        try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement()) {
            sql.execute("create schema "+schema);
            sql.execute("set search_path to "+schema+",public");
            if(legacy) {
                for(String migration:new String[]{"V1__integrate_client_files_with_localroots.sql",
                        "V2__support_estimator_source_and_file_browsing.sql","V3__add_quotes_attachment_category.sql",
                        "V4__estimator_quote_contact_links.sql","V5__allow_named_quote_contacts.sql"}) {
                    try(var input=getClass().getResourceAsStream("/db/migration/"+migration)) {
                        assertNotNull(input);sql.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8));
                    }
                }
                sql.execute("create table flyway_schema_history(version varchar(50))");
                sql.execute("insert into flyway_schema_history(version) values('104')");
                sql.execute("insert into tenants(id,name,slug) values('"+tenant+"','Test','test')");
                sql.execute("insert into contacts(id,tenant_id,first_name,created_at,updated_at) values('"+contact+"','"+tenant+"','Test',now(),now())");
                sql.execute("insert into contact_attachments(id,tenant_id,contact_id,file_name,s3_bucket,s3_key,source_system,category,created_at) values('"+file+"','"+tenant+"','"+contact+"','quote.pdf','test','existing/key','ESTIMATOR','QUOTES',now())");
                // Exercise adoption where earlier scripts were applied incompletely: QUOTES exists but its check does not.
                sql.execute("alter table contact_attachments drop constraint contact_attachments_category_check");
            }
            Flyway flyway=Flyway.configure().dataSource(url,user,password).defaultSchema(schema).schemas(schema)
                    .table("client_files_flyway_schema_history").baselineOnMigrate(true).baselineVersion("99")
                    .locations("classpath:db/client-files-migration").cleanDisabled(true).load();
            assertEquals(1,flyway.migrate().migrationsExecuted);
            assertEquals("100",flyway.info().current().getVersion().toString());
            assertEquals(0,flyway.migrate().migrationsExecuted);
            flyway.validate();
            if(legacy) {
                try(var rows=sql.executeQuery("select s3_key,category from contact_attachments where id='"+file+"'")) {
                    assertTrue(rows.next());assertEquals("existing/key",rows.getString(1));assertEquals("QUOTES",rows.getString(2));
                }
                try(var rows=sql.executeQuery("select version from flyway_schema_history")) {
                    assertTrue(rows.next());assertEquals("104",rows.getString(1));assertFalse(rows.next());
                }
            }
            // Current CRM state/default must work after both fresh and adoption migrations.
            if(!legacy)sql.execute("insert into tenants(id,name,slug) values('"+tenant+"','Test','test')");
            sql.execute("insert into contacts(id,tenant_id,first_name,created_at,updated_at,follow_up_action) values('"+UUID.randomUUID()+"','"+tenant+"','Next',now(),now(),'SEND_ESTIMATE')");
            try(var rows=sql.executeQuery("select pipeline_status from contacts where first_name='Next'")) {
                assertTrue(rows.next());assertEquals("INQUIRY",rows.getString(1));
            }
        } finally {
            try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement()) {
                sql.execute("drop schema if exists "+schema+" cascade");
            }
        }
    }
}

package com.localroots.clientfiles.security;

import com.localroots.clientfiles.common.ApiException;
import com.localroots.clientfiles.tenant.TenantEntity;
import com.localroots.clientfiles.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private final UUID tenantId = UUID.randomUUID();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private JdbcTemplate db;
    private AuthService auth;
    private JwtService tokens;

    @BeforeEach void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE app_users(id UUID, email VARCHAR(255), password_hash TEXT, role VARCHAR(30), active BOOLEAN, tenant_id UUID)");
        db.update("INSERT INTO app_users VALUES (?, ?, ?, 'TENANT_USER', true, ?)", UUID.randomUUID(), "crystal@example.com", passwords.encode("shared password"), tenantId);
        var properties = new AuthenticationProperties(); properties.setTenantId(tenantId.toString());
        tokens = mock(JwtService.class);
        when(tokens.issue(eq("crystal@example.com"), eq(tenantId), anyString())).thenReturn(new JwtService.IssuedToken("test-token", Instant.now().plusSeconds(60)));
        var tenants = mock(TenantService.class); var tenant = mock(TenantEntity.class);
        when(tenant.getName()).thenReturn("Texas Top Dressing"); when(tenants.requireTenant(tenantId)).thenReturn(tenant);
        auth = new AuthService(properties, passwords, tokens, tenants, db);
    }

    @Test void usesCrmPasswordAndSeesChangesImmediatelyOnNextLogin() {
        assertEquals("crystal@example.com", auth.login(" CRYSTAL@example.com ", "shared password").username());
        db.update("UPDATE app_users SET password_hash = ?", passwords.encode("new password"));
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class, () -> auth.login("crystal@example.com", "shared password")).getStatus());
        assertEquals("test-token", auth.login("crystal@example.com", "new password").accessToken());
    }

    @Test void rejectsWrongUnknownInactiveAndOtherBusinessAccounts() {
        for (String email : new String[]{"missing@example.com", "crystal@example.com"}) {
            assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class, () -> auth.login(email, "wrong")).getStatus());
        }
        db.update("UPDATE app_users SET active = false");
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class, () -> auth.login("crystal@example.com", "shared password")).getStatus());
        db.update("UPDATE app_users SET active = true, tenant_id = ?", UUID.randomUUID());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> auth.login("crystal@example.com", "shared password")).getStatus());
        verify(tokens, never()).issue(any(), any(), any());
        db.update("UPDATE app_users SET role = 'ADMIN', tenant_id = NULL");
        assertEquals("test-token", auth.login("crystal@example.com", "shared password").accessToken());
    }

    @Test void identityDatabaseFailureDoesNotFallBackToAnEnvironmentPassword() {
        db.execute("DROP TABLE app_users");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, () -> auth.login("crystal@example.com", "shared password")).getStatus());
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test void separateFileDatabaseReadsOnlyTheConfiguredIdentityStore() {
        var identities = new DriverManagerDataSource("jdbc:h2:mem:separate-identities-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var identityDb = new JdbcTemplate(identities);
        identityDb.execute("CREATE TABLE app_users(email VARCHAR(255), password_hash TEXT, role VARCHAR(30), active BOOLEAN, tenant_id UUID)");
        identityDb.update("INSERT INTO app_users VALUES (?, ?, 'TENANT_USER', true, ?)", "crystal@example.com", passwords.encode("identity password"), tenantId);
        var properties = new AuthenticationProperties();
        properties.setTenantId(tenantId.toString()); properties.setIdentityDatabaseUrl(identities.getUrl());
        properties.setIdentityDatabaseUsername("sa"); properties.setIdentityDatabasePassword("");
        var tenant = mock(TenantEntity.class); when(tenant.getName()).thenReturn("Development");
        var tenants = mock(TenantService.class); when(tenants.requireTenant(tenantId)).thenReturn(tenant);
        var separate = new AuthService(properties, passwords, tokens, tenants, db);
        assertEquals("test-token", separate.login("crystal@example.com", "identity password").accessToken());
        // The file database contains a different hash; it must never win or act as fallback.
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> separate.login("crystal@example.com", "shared password")).getStatus());
        identityDb.execute("DROP TABLE app_users");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class,
                () -> separate.login("crystal@example.com", "shared password")).getStatus());
    }
}


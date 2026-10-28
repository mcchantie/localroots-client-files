package com.localroots.clientfiles.security;

import com.localroots.clientfiles.common.ApiException;
import com.localroots.clientfiles.tenant.TenantEntity;
import com.localroots.clientfiles.tenant.TenantService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/** Uses the same persisted email and BCrypt hash as CRM. No separate file-app account. */
@Service
public class AuthService {
    private final JdbcTemplate identities;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TenantService tenantService;
    private final String dummyHash;
    private final UUID tenantId;

    public AuthService(AuthenticationProperties properties, PasswordEncoder passwordEncoder,
                       JwtService jwtService, TenantService tenantService, JdbcTemplate identities) {
        String identityUrl = properties.getIdentityDatabaseUrl();
        this.identities = identityUrl == null || identityUrl.isBlank() ? identities
                : new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        identityUrl, properties.getIdentityDatabaseUsername(), properties.getIdentityDatabasePassword()));
        this.identities.setQueryTimeout(10);
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tenantService = tenantService;
        this.dummyHash = passwordEncoder.encode("unused-dummy-password");
        try {
            this.tenantId = UUID.fromString(properties.getTenantId().trim());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("CLIENT_FILES_TENANT_ID must be an existing Local Roots tenant UUID.", exception);
        }
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String password) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Account account;
        try {
            account = identities.query("""
                    SELECT email, password_hash, active, role, tenant_id FROM app_users
                    WHERE lower(email) = ?
                    """, (rs, row) -> new Account(rs.getString("email"), rs.getString("password_hash"),
                    rs.getBoolean("active"), rs.getString("role"), rs.getString("tenant_id")),
                    normalized).stream().findFirst().orElse(null);
        } catch (DataAccessException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Sign-in unavailable", "Shared Local Roots sign-in is unavailable.");
        }
        String hash = account == null ? dummyHash : account.passwordHash();
        boolean valid = password != null && password.length() <= 1024 && passwordEncoder.matches(password, hash);
        if (!valid || account == null || !account.active()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials", "The email or password is incorrect.");
        }
        if (!"ADMIN".equals(account.role()) && !("TENANT_USER".equals(account.role())
                && tenantId.toString().equals(account.tenantId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Access denied", "This account cannot access this business.");
        }
        TenantEntity tenant = tenantService.requireTenant(tenantId);
        String canonicalEmail = account.email();
        JwtService.IssuedToken token = jwtService.issue(canonicalEmail, tenantId, account.role());
        return new LoginResult(token.value(), token.expiresAt(), canonicalEmail, tenantId, tenant.getName());
    }

    private record Account(String email, String passwordHash, boolean active, String role, String tenantId) {}

    public record LoginResult(String accessToken, java.time.Instant expiresAt, String username,
                              UUID tenantId, String tenantName) {}
}


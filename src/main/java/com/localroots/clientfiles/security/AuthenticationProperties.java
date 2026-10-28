package com.localroots.clientfiles.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "client-files.auth")
public class AuthenticationProperties {

    private String identityDatabaseUrl;
    private String identityDatabaseUsername;
    private String identityDatabasePassword;
    public String getIdentityDatabaseUrl() { return identityDatabaseUrl; }
    public void setIdentityDatabaseUrl(String value) { identityDatabaseUrl = value; }
    public String getIdentityDatabaseUsername() { return identityDatabaseUsername; }
    public void setIdentityDatabaseUsername(String value) { identityDatabaseUsername = value; }
    public String getIdentityDatabasePassword() { return identityDatabasePassword; }
    public void setIdentityDatabasePassword(String value) { identityDatabasePassword = value; }
    private String tenantId;
    private String jwtSecretBase64;
    private String jwtIssuer = "client-files-api";
    private Duration accessTokenTtl = Duration.ofHours(8);

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getJwtSecretBase64() {
        return jwtSecretBase64;
    }

    public void setJwtSecretBase64(String jwtSecretBase64) {
        this.jwtSecretBase64 = jwtSecretBase64;
    }

    public String getJwtIssuer() {
        return jwtIssuer;
    }

    public void setJwtIssuer(String jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }
}



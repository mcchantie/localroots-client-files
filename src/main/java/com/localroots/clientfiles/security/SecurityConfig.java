package com.localroots.clientfiles.security;

import com.localroots.clientfiles.config.CorsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecretKey jwtSecretKey(AuthenticationProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.getJwtSecretBase64());
        } catch (IllegalArgumentException | NullPointerException exception) {
            decoded = new byte[32];
        }
        if (decoded.length < 32) {
            decoded = new byte[32];
        }
        return new SecretKeySpec(decoded, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            SecretKey jwtSecretKey,
            AuthenticationProperties properties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getJwtIssuer()));
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // This single internal operation authenticates the service key in its controller.
                        .requestMatchers(HttpMethod.POST, "/api/internal/estimator/attachments/*/quote-request").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) -> {
                            log.warn(
                                    "Authentication required method={} path={} reason={}",
                                    request.getMethod(),
                                    request.getRequestURI(),
                                    exception.getClass().getSimpleName()
                            );
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.getWriter().write("{\"title\":\"Authentication required\",\"status\":401,\"detail\":\"Send a valid Bearer token.\"}");
                        })
                )
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler((request, response, exception) -> {
                            log.warn(
                                    "Access denied method={} path={} reason={}",
                                    request.getMethod(),
                                    request.getRequestURI(),
                                    exception.getClass().getSimpleName()
                            );
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.getWriter().write("{\"title\":\"Access denied\",\"status\":403,\"detail\":\"You do not have permission to perform this action.\"}");
                        })
                )
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }

    @Bean
    public ApplicationRunner productionSecurityValidator(
            AuthenticationProperties auth,
            ClientFilesSecurityProperties security,
            CorsProperties cors,
            Environment environment
    ) {
        return arguments -> {
            boolean production = Arrays.asList(environment.getActiveProfiles()).contains("production");
            if (!production) {
                return;
            }

            requireText(auth.getAdminUsername(), "CLIENT_FILES_ADMIN_USERNAME must be configured in production.");
            if (auth.getAdminPassword() == null || auth.getAdminPassword().length() < 16) {
                throw new IllegalStateException("CLIENT_FILES_ADMIN_PASSWORD must be at least 16 characters in production.");
            }
            requireText(auth.getTenantId(), "CLIENT_FILES_TENANT_ID must be configured in production.");
            try {
                UUID.fromString(auth.getTenantId().trim());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("CLIENT_FILES_TENANT_ID must be a valid UUID.", exception);
            }

            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(auth.getJwtSecretBase64());
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalStateException("CLIENT_FILES_JWT_SECRET_BASE64 must be valid base64.", exception);
            }
            if (secret.length < 32) {
                throw new IllegalStateException("CLIENT_FILES_JWT_SECRET_BASE64 must decode to at least 32 bytes.");
            }
            if (auth.getAccessTokenTtl() == null || auth.getAccessTokenTtl().isNegative() || auth.getAccessTokenTtl().isZero()) {
                throw new IllegalStateException("CLIENT_FILES_ACCESS_TOKEN_TTL must be greater than zero.");
            }
            if (security.isAllowTenantHeader()) {
                throw new IllegalStateException("CLIENT_FILES_ALLOW_TENANT_HEADER must remain false in production.");
            }
            if (cors.getAllowedOrigins().isEmpty() && cors.getAllowedOriginPatterns().isEmpty()) {
                throw new IllegalStateException("Configure at least one Base44 origin using CLIENT_FILES_ALLOWED_ORIGINS or CLIENT_FILES_ALLOWED_ORIGIN_PATTERNS.");
            }
            log.info("Production security configuration validation completed successfully");
        };
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }
}

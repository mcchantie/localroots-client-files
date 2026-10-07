package com.localroots.clientfiles.api;

import com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService;
import com.localroots.clientfiles.common.GlobalExceptionHandler;
import com.localroots.clientfiles.config.CorsProperties;
import com.localroots.clientfiles.security.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EstimatorIntegrationSecurityTest {
    static final String KEY="test-shared-service-key-with-at-least-32-characters";
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class,EstimatorIntegrationController.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean AuthenticationProperties authenticationProperties() {return new AuthenticationProperties();}
        @Bean ClientFilesSecurityProperties securityProperties() {return new ClientFilesSecurityProperties();}
        @Bean CorsProperties corsProperties() {return new CorsProperties();}
        @Bean EstimateQuoteAssignmentService assignments() {return mock(EstimateQuoteAssignmentService.class);}
    }
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    UUID attachment=UUID.randomUUID(),estimate=UUID.randomUUID(),tenant=UUID.randomUUID(),contact=UUID.randomUUID();
    String path() {return "/api/internal/estimator/attachments/"+attachment+"/quote-request";}
    String body() {return "{\"estimateId\":\""+estimate+"\",\"firstName\":\"Mary\",\"lastName\":\"Smith\"}";}
    @BeforeEach void setup() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("CLIENT_FILES_ESTIMATOR_API_KEY",KEY)));
        context.register(Config.class);context.refresh();
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
        when(context.getBean(EstimateQuoteAssignmentService.class).assign(eq(tenant),eq(attachment),any())).thenReturn(new EstimateQuoteAssignmentService.Response(contact));
    }
    @AfterEach void close() {if(context!=null)context.close();}
    @Test void serviceKeyWorksWithoutBearerOnlyOnTheInternalOperation() throws Exception {
        mvc.perform(post(path()).contentType("application/json").content(body()).header("X-Estimator-Key",KEY).header("X-Estimator-Tenant",tenant.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.contactId").value(contact.toString()));
        mvc.perform(get("/api/v1/attachments").header("X-Estimator-Key",KEY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/estimator/other").header("X-Estimator-Key",KEY)).andExpect(status().isUnauthorized());
    }
    @Test void missingOrWrongServiceKeyIsRejectedByTheActualHttpEndpoint() throws Exception {
        mvc.perform(post(path()).contentType("application/json").content(body()).header("X-Estimator-Tenant",tenant.toString())).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).contentType("application/json").content(body()).header("X-Estimator-Key","wrong").header("X-Estimator-Tenant",tenant.toString())).andExpect(status().isUnauthorized());
        verify(context.getBean(EstimateQuoteAssignmentService.class),never()).assign(any(),any(),any());
    }
}

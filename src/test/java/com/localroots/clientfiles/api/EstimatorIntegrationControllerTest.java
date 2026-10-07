package com.localroots.clientfiles.api;

import com.localroots.clientfiles.attachment.*;
import com.localroots.clientfiles.common.ApiException;
import com.localroots.clientfiles.contact.ContactService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EstimatorIntegrationControllerTest {
    final String key="a-shared-server-integration-key-over-32-characters";
    final UUID tenantA=UUID.randomUUID(),tenantB=UUID.randomUUID(),attachment=UUID.randomUUID(),estimate=UUID.randomUUID();
    final EstimateQuoteAssignmentService assignments=mock(EstimateQuoteAssignmentService.class);
    final EstimatorIntegrationController controller=new EstimatorIntegrationController(assignments,key);
    EstimateQuoteAssignmentService.Request request() {return new EstimateQuoteAssignmentService.Request(estimate,"Mary","Smith",null,null);}
    @Test void authenticatesTheServiceAndSupportsDifferentTenantsWithoutAccountLogin() {
        var a=new EstimateQuoteAssignmentService.Response(UUID.randomUUID());var b=new EstimateQuoteAssignmentService.Response(UUID.randomUUID());
        var body=request();when(assignments.assign(tenantA,attachment,body)).thenReturn(a);when(assignments.assign(tenantB,attachment,body)).thenReturn(b);
        assertEquals(a,controller.assignQuote(attachment,key,tenantA.toString(),body));
        assertEquals(b,controller.assignQuote(attachment,key,tenantB.toString(),body));
        verify(assignments).assign(tenantA,attachment,body);verify(assignments).assign(tenantB,attachment,body);
    }
    @Test void missingOrWrongServiceKeyCannotAssignAnything() {
        for(String supplied:new String[]{null,"wrong"}) {
            var error=assertThrows(ApiException.class,()->controller.assignQuote(attachment,supplied,tenantA.toString(),request()));
            assertEquals(HttpStatus.UNAUTHORIZED,error.getStatus());
        }
        verifyNoInteractions(assignments);
    }
    @Test void integrationIsDisabledWithoutAStrongConfiguredKey() {
        var disabled=new EstimatorIntegrationController(assignments,"");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ApiException.class,()->disabled.assignQuote(attachment,key,tenantA.toString(),request())).getStatus());
        verifyNoInteractions(assignments);
    }
    @Test void missingOrMalformedTenantCannotAssignAnything() {
        for(String tenant:new String[]{null,"not-a-uuid"}) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException.class,()->controller.assignQuote(attachment,key,tenant,request())).getStatus());
        }
        verifyNoInteractions(assignments);
    }
    @Test void validServiceKeyCannotMoveAnAttachmentUnderTheWrongTenant() {
        var repo=mock(AttachmentRepository.class);var contacts=mock(ContactService.class);var jdbc=mock(JdbcTemplate.class);
        when(repo.lockForEstimateQuote(attachment,tenantA)).thenReturn(Optional.empty());
        var scopedController=new EstimatorIntegrationController(new EstimateQuoteAssignmentService(repo,contacts,jdbc),key);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->scopedController.assignQuote(attachment,key,tenantA.toString(),request())).getStatus());
        verify(repo).lockForEstimateQuote(attachment,tenantA);verifyNoInteractions(contacts,jdbc);
    }
}

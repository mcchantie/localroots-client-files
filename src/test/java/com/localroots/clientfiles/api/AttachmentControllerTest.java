package com.localroots.clientfiles.api;

import com.localroots.clientfiles.attachment.AttachmentService;
import com.localroots.clientfiles.attachment.AttachmentSortField;
import com.localroots.clientfiles.security.RequestTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PatchMapping;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentControllerTest {

    @Test
    void quoteAssignmentUsesTheAuthenticatedTenant() {
        var assignments=mock(com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.class);
        var tenantResolver=mock(RequestTenantResolver.class);var request=mock(HttpServletRequest.class);
        UUID tenant=UUID.randomUUID(),attachment=UUID.randomUUID(),contact=UUID.randomUUID();
        var body=new com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.Request(UUID.randomUUID(),"Mary","Smith",null,null);
        var result=new com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.Response(contact);
        when(tenantResolver.requireTenantId(request)).thenReturn(tenant);when(assignments.assign(tenant,attachment,body)).thenReturn(result);
        var controller=new AttachmentController(mock(AttachmentService.class),tenantResolver,assignments);
        org.junit.jupiter.api.Assertions.assertEquals(result,controller.assignEstimateQuote(request,attachment,body));
        verify(assignments).assign(tenant,attachment,body);
    }

    @Test
    void permanentDeleteUsesSeparateEndpointAndTenant() throws Exception {
        Method method = AttachmentController.class.getDeclaredMethod(
                "permanentlyDeleteAttachment", HttpServletRequest.class, UUID.class);
        assertArrayEquals(new String[]{"/{attachmentId}/permanent"}, method.getAnnotation(DeleteMapping.class).value());
        assertSame(HttpStatus.NO_CONTENT, method.getAnnotation(ResponseStatus.class).value());

        AttachmentService service = mock(AttachmentService.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID tenantId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(tenantResolver.requireTenantId(request)).thenReturn(tenantId);

        new AttachmentController(service, tenantResolver, mock(com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.class)).permanentlyDeleteAttachment(request, attachmentId);

        verify(service).permanentlyDelete(tenantId, attachmentId);
    }

    @Test
    void exposesPatchEndpointForAssignmentUpdates() throws Exception {
        Method method = AttachmentController.class.getDeclaredMethod(
                "updateAttachmentAssignment",
                HttpServletRequest.class,
                UUID.class,
                AssignAttachmentRequest.class
        );

        PatchMapping mapping = method.getAnnotation(PatchMapping.class);

        assertTrue(mapping != null);
        assertArrayEquals(new String[]{"/{attachmentId}"}, mapping.value());
    }

    @Test
    void patchAssignsAttachmentToSelectedContact() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AttachmentResponse expected = mock(AttachmentResponse.class);

        AttachmentController controller = new AttachmentController(attachmentService, tenantResolver, mock(com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.class));
        UUID tenantId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();

        when(tenantResolver.requireTenantId(request)).thenReturn(tenantId);
        when(attachmentService.assignToContact(tenantId, attachmentId, contactId)).thenReturn(expected);

        AttachmentResponse actual = controller.updateAttachmentAssignment(
                request,
                attachmentId,
                new AssignAttachmentRequest(contactId)
        );

        assertSame(expected, actual);
        verify(attachmentService).assignToContact(tenantId, attachmentId, contactId);
    }

    @Test
    void patchWithNullContactIdMovesAttachmentToUnassigned() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AttachmentResponse expected = mock(AttachmentResponse.class);

        AttachmentController controller = new AttachmentController(attachmentService, tenantResolver, mock(com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.class));
        UUID tenantId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();

        when(tenantResolver.requireTenantId(request)).thenReturn(tenantId);
        when(attachmentService.assignToContact(tenantId, attachmentId, null)).thenReturn(expected);

        AttachmentResponse actual = controller.updateAttachmentAssignment(
                request,
                attachmentId,
                new AssignAttachmentRequest(null)
        );

        assertSame(expected, actual);
        verify(attachmentService).assignToContact(tenantId, attachmentId, null);
    }
    @Test
    void deletedOnlyListRequestIsForwardedToService() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PageResponse<AttachmentResponse> expected = new PageResponse<>(
                java.util.List.of(),
                0,
                25,
                0,
                0,
                true,
                true
        );

        AttachmentController controller = new AttachmentController(attachmentService, tenantResolver, mock(com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.class));
        UUID tenantId = UUID.randomUUID();
        when(tenantResolver.requireTenantId(request)).thenReturn(tenantId);
        when(attachmentService.list(
                tenantId,
                null,
                "estimate",
                null,
                null,
                null,
                false,
                false,
                true,
                AttachmentSortField.NAME,
                Sort.Direction.ASC,
                0,
                25
        )).thenReturn(expected);

        PageResponse<AttachmentResponse> actual = controller.listAttachments(
                request,
                null,
                "estimate",
                null,
                null,
                null,
                false,
                false,
                true,
                AttachmentSortField.NAME,
                Sort.Direction.ASC,
                0,
                25
        );

        assertSame(expected, actual);
        verify(attachmentService).list(
                tenantId,
                null,
                "estimate",
                null,
                null,
                null,
                false,
                false,
                true,
                AttachmentSortField.NAME,
                Sort.Direction.ASC,
                0,
                25
        );
    }

    @Test
    void returnsUtf8TextContentWithExplicitCharset() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        RequestTenantResolver tenantResolver = mock(RequestTenantResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AttachmentController controller = new AttachmentController(attachmentService, tenantResolver, mock(com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.class));
        UUID tenantId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] expected = "• Estimate — ~5,420 sq ft".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        when(tenantResolver.requireTenantId(request)).thenReturn(tenantId);
        when(attachmentService.readTextContent(tenantId, attachmentId)).thenReturn(expected);

        ResponseEntity<byte[]> response = controller.getTextContent(request, attachmentId);

        assertArrayEquals(expected, response.getBody());
        assertTrue(response.getHeaders().getContentType().toString().toLowerCase().contains("charset=utf-8"));
        verify(attachmentService).readTextContent(tenantId, attachmentId);
    }

}

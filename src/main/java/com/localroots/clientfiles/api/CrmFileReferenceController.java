package com.localroots.clientfiles.api;

import com.localroots.clientfiles.attachment.AttachmentRepository;
import com.localroots.clientfiles.attachment.AttachmentStatus;
import com.localroots.clientfiles.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Narrow, tenant-scoped server-to-server ownership check; never exposes storage credentials. */
@RestController
@RequestMapping("/api/internal/crm/contacts")
public class CrmFileReferenceController {
    private final AttachmentRepository attachments;
    private final String apiKey;
    private final String allowedTenant;
    public CrmFileReferenceController(AttachmentRepository attachments,
            @Value("${CLIENT_FILES_CRM_API_KEY:}") String apiKey,
            @Value("${CLIENT_FILES_CRM_TENANT_ID:${CLIENT_FILES_TENANT_ID:}}") String allowedTenant) {
        this.attachments=attachments; this.apiKey=apiKey; this.allowedTenant=allowedTenant;
    }
    @GetMapping("/{contactId}/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@PathVariable UUID contactId, @PathVariable UUID fileId,
            @RequestHeader(value="X-CRM-Key",required=false) String suppliedKey,
            @RequestHeader(value="X-CRM-Tenant",required=false) String tenantHeader) {
        if(apiKey.length()<32 || allowedTenant.isBlank())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CRM integration is not configured","Configure a dedicated service key and tenant.");
        if(suppliedKey==null || !MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8),suppliedKey.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED,"Invalid service credentials","A valid CRM service key is required.");
        UUID tenantId;
        try { tenantId=UUID.fromString(tenantHeader); }
        catch(IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid tenant","Send the configured CRM tenant ID.");
        }
        if(!tenantId.toString().equalsIgnoreCase(allowedTenant.trim()))
            throw new ApiException(HttpStatus.FORBIDDEN,"Tenant is not authorized","This service key cannot access that tenant.");
        var file=attachments.findByIdAndTenantId(fileId,tenantId)
                .filter(a->contactId.equals(a.getContactId()) && a.getDeletedAt()==null && a.getStatus()==AttachmentStatus.READY)
                .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"File not found","No ready file belongs to this contact and tenant."));
    }
}

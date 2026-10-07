package com.localroots.clientfiles.api;

import com.localroots.clientfiles.attachment.AttachmentCategory;
import com.localroots.clientfiles.attachment.AttachmentFileKind;
import com.localroots.clientfiles.attachment.AttachmentService;
import com.localroots.clientfiles.attachment.AttachmentSortField;
import com.localroots.clientfiles.attachment.AttachmentStatus;
import com.localroots.clientfiles.security.RequestTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attachments")
@Validated
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final RequestTenantResolver tenantResolver;
    private final com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService estimateQuotes;

    public AttachmentController(AttachmentService attachmentService, RequestTenantResolver tenantResolver, com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService estimateQuotes) {
        this.attachmentService = attachmentService;
        this.tenantResolver = tenantResolver;
        this.estimateQuotes = estimateQuotes;
    }

    @PostMapping("/{attachmentId}/estimator-quote-request")
    public com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.Response assignEstimateQuote(
            HttpServletRequest request, @PathVariable UUID attachmentId,
            @Valid @RequestBody com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService.Request body) {
        return estimateQuotes.assign(tenantResolver.requireTenantId(request),attachmentId,body);
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public InitializeUploadResponse initializeUpload(
            HttpServletRequest servletRequest,
            @Valid @RequestBody InitializeUploadRequest request
    ) {
        return attachmentService.initializeUpload(tenantResolver.requireTenantId(servletRequest), request);
    }

    @PostMapping("/{attachmentId}/complete")
    public AttachmentResponse completeUpload(
            HttpServletRequest request,
            @PathVariable UUID attachmentId
    ) {
        return attachmentService.completeUpload(tenantResolver.requireTenantId(request), attachmentId);
    }

    @GetMapping("/{attachmentId}")
    public AttachmentResponse getAttachment(
            HttpServletRequest request,
            @PathVariable UUID attachmentId,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        return attachmentService.get(tenantResolver.requireTenantId(request), attachmentId, includeDeleted);
    }

    @GetMapping(value = "/{attachmentId}/text-content", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<byte[]> getTextContent(
            HttpServletRequest request,
            @PathVariable UUID attachmentId
    ) {
        byte[] content = attachmentService.readTextContent(
                tenantResolver.requireTenantId(request),
                attachmentId
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    @GetMapping
    public PageResponse<AttachmentResponse> listAttachments(
            HttpServletRequest request,
            @RequestParam(required = false) UUID contactId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AttachmentCategory category,
            @RequestParam(required = false) AttachmentFileKind fileKind,
            @RequestParam(required = false) AttachmentStatus status,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean deletedOnly,
            @RequestParam(defaultValue = "CREATED_AT") AttachmentSortField sortBy,
            @RequestParam(defaultValue = "DESC") Sort.Direction sortDirection,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size
    ) {
        return attachmentService.list(
                tenantResolver.requireTenantId(request),
                contactId,
                search,
                category,
                fileKind,
                status,
                unassigned,
                includeDeleted,
                deletedOnly,
                sortBy,
                sortDirection,
                page,
                size
        );
    }

    @PostMapping("/batch-update")
    public BatchUpdateAttachmentsResponse batchUpdate(
            HttpServletRequest request,
            @Valid @RequestBody BatchUpdateAttachmentsRequest body
    ) {
        return attachmentService.batchUpdate(
                tenantResolver.requireTenantId(request),
                body
        );
    }

    @PostMapping("/{attachmentId}/download-url")
    public DownloadUrlResponse createDownloadUrl(
            HttpServletRequest request,
            @PathVariable UUID attachmentId,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        return attachmentService.createDownloadUrl(tenantResolver.requireTenantId(request), attachmentId, download);
    }

    @DeleteMapping("/{attachmentId}")
    public AttachmentResponse deleteAttachment(
            HttpServletRequest request,
            @PathVariable UUID attachmentId
    ) {
        return attachmentService.softDelete(tenantResolver.requireTenantId(request), attachmentId);
    }

    @DeleteMapping("/{attachmentId}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permanentlyDeleteAttachment(
            HttpServletRequest request,
            @PathVariable UUID attachmentId
    ) {
        attachmentService.permanentlyDelete(tenantResolver.requireTenantId(request), attachmentId);
    }

    @PostMapping("/{attachmentId}/restore")
    public AttachmentResponse restoreAttachment(
            HttpServletRequest request,
            @PathVariable UUID attachmentId
    ) {
        return attachmentService.restore(tenantResolver.requireTenantId(request), attachmentId);
    }

    @PatchMapping("/{attachmentId}")
    public AttachmentResponse updateAttachmentAssignment(
            HttpServletRequest request,
            @PathVariable UUID attachmentId,
            @RequestBody AssignAttachmentRequest body
    ) {
        return attachmentService.assignToContact(
                tenantResolver.requireTenantId(request),
                attachmentId,
                body.contactId()
        );
    }

    @PatchMapping("/{attachmentId}/display-name")
    public AttachmentResponse renameAttachment(
            HttpServletRequest request,
            @PathVariable UUID attachmentId,
            @Valid @RequestBody RenameAttachmentRequest body
    ) {
        return attachmentService.rename(
                tenantResolver.requireTenantId(request), attachmentId, body.displayName());
    }

    @PostMapping("/{attachmentId}/assign")
    public AttachmentResponse assignAttachment(
            HttpServletRequest request,
            @PathVariable UUID attachmentId,
            @RequestBody AssignAttachmentRequest body
    ) {
        return attachmentService.assignToContact(
                tenantResolver.requireTenantId(request),
                attachmentId,
                body.contactId()
        );
    }
}

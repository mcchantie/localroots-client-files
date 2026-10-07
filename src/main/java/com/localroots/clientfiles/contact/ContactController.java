package com.localroots.clientfiles.contact;

import com.localroots.clientfiles.api.AttachmentResponse;
import com.localroots.clientfiles.api.PageResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contacts")
@Validated
public class ContactController {

    private final ContactService contactService;
    private final AttachmentService attachmentService;
    private final RequestTenantResolver tenantResolver;

    public ContactController(
            ContactService contactService,
            AttachmentService attachmentService,
            RequestTenantResolver tenantResolver
    ) {
        this.contactService = contactService;
        this.attachmentService = attachmentService;
        this.tenantResolver = tenantResolver;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContactResponse create(HttpServletRequest request, @Valid @RequestBody ContactRequest body) {
        return contactService.create(tenantResolver.requireTenantId(request), body);
    }

    @GetMapping
    public PageResponse<ContactResponse> list(
            HttpServletRequest request,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
    ) {
        String term=search==null || search.isBlank()?q:search;
        return contactService.list(tenantResolver.requireTenantId(request), term, page, size);
    }

    @GetMapping("/{contactId}")
    public ContactResponse get(HttpServletRequest request, @PathVariable UUID contactId) {
        return contactService.get(tenantResolver.requireTenantId(request), contactId);
    }

    @GetMapping("/{contactId}/attachments")
    public PageResponse<AttachmentResponse> listAttachments(
            HttpServletRequest request,
            @PathVariable UUID contactId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AttachmentCategory category,
            @RequestParam(required = false) AttachmentFileKind fileKind,
            @RequestParam(required = false) AttachmentStatus status,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean deletedOnly,
            @RequestParam(defaultValue = "CREATED_AT") AttachmentSortField sortBy,
            @RequestParam(defaultValue = "DESC") Sort.Direction sortDirection,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size
    ) {
        UUID tenantId = tenantResolver.requireTenantId(request);

        contactService.get(tenantId, contactId);

        return attachmentService.list(
                tenantId,
                contactId,
                search,
                category,
                fileKind,
                status,
                false,
                includeDeleted,
                deletedOnly,
                sortBy,
                sortDirection,
                page,
                size
        );
    }

    @PutMapping("/{contactId}")
    public ContactResponse update(
            HttpServletRequest request,
            @PathVariable UUID contactId,
            @Valid @RequestBody ContactRequest body
    ) {
        return contactService.update(tenantResolver.requireTenantId(request), contactId, body);
    }
}

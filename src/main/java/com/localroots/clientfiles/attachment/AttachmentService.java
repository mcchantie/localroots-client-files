package com.localroots.clientfiles.attachment;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.localroots.clientfiles.api.AttachmentResponse;
import com.localroots.clientfiles.api.BatchUpdateAttachmentsRequest;
import com.localroots.clientfiles.api.BatchUpdateAttachmentsResponse;
import com.localroots.clientfiles.api.DownloadUrlResponse;
import com.localroots.clientfiles.api.InitializeUploadRequest;
import com.localroots.clientfiles.api.InitializeUploadResponse;
import com.localroots.clientfiles.api.PageResponse;
import com.localroots.clientfiles.common.ApiException;
import com.localroots.clientfiles.common.UploadVerificationException;
import com.localroots.clientfiles.contact.ContactService;
import com.localroots.clientfiles.security.ClientFilesSecurityProperties;
import com.localroots.clientfiles.storage.S3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository repository;
    private final S3StorageService storageService;
    private final ClientFilesSecurityProperties securityProperties;
    private final ContactService contactService;
    private final ObjectMapper objectMapper;

    public AttachmentService(
            AttachmentRepository repository,
            S3StorageService storageService,
            ClientFilesSecurityProperties securityProperties,
            ContactService contactService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.storageService = storageService;
        this.securityProperties = securityProperties;
        this.contactService = contactService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InitializeUploadResponse initializeUpload(UUID tenantId, InitializeUploadRequest request) {
        log.info(
                "Initializing attachment upload contactId={} estimateId={} parentAttachmentId={} category={} originalFileName={} sizeBytes={} contentType={}",
                request.contactId(),
                request.estimateId(),
                request.parentAttachmentId(),
                request.category(),
                request.originalFileName(),
                request.sizeBytes(),
                request.contentType()
        );
        String sourceSystem = normalizeSourceSystem(request.sourceSystem());
        validateRelatedRecords(
                tenantId,
                request.contactId(),
                request.estimateId(),
                request.parentAttachmentId(),
                sourceSystem
        );

        String contentType = storageService.canonicalContentType(request.contentType());
        validateContentType(contentType);
        validateFileSize(request.sizeBytes());
        validateTextFileSize(contentType, request.sizeBytes());

        AttachmentFileKind fileKind = inferFileKind(contentType);
        validateCategory(request.category(), fileKind);

        UUID attachmentId = UUID.randomUUID();
        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? request.originalFileName().trim()
                : request.displayName().trim();
        String metadataJson = toJson(request.metadata());
        String key = storageService.buildAttachmentKey(
                tenantId,
                request.contactId(),
                attachmentId,
                request.category(),
                request.originalFileName()
        );

        AttachmentEntity entity = AttachmentEntity.pending(
                attachmentId,
                tenantId,
                request.contactId(),
                request.estimateId(),
                request.parentAttachmentId(),
                request.category(),
                fileKind,
                request.originalFileName().trim(),
                displayName,
                contentType,
                request.sizeBytes(),
                blankToNull(request.checksumSha256Base64()),
                storageService.bucket(),
                key,
                sourceSystem,
                blankToNull(request.description()),
                metadataJson,
                null
        );

        repository.save(entity);
        log.info(
                "Pending attachment created attachmentId={} contactId={} status={} fileKind={} s3Bucket={} s3Key={}",
                entity.getId(),
                entity.getContactId(),
                entity.getStatus(),
                entity.getFileKind(),
                entity.getS3Bucket(),
                entity.getS3Key()
        );

        S3StorageService.PresignedUpload upload = storageService.presignUpload(
                entity.getS3Key(),
                entity.getContentType(),
                entity.getChecksumSha256Base64()
        );

        log.info(
                "Attachment upload initialized attachmentId={} expiresAt={} requiredHeaderNames={}",
                entity.getId(),
                upload.expiresAt(),
                upload.requiredHeaders().keySet()
        );

        return new InitializeUploadResponse(
                entity.getId(),
                entity.getStatus(),
                "PUT",
                upload.url(),
                upload.expiresAt(),
                upload.requiredHeaders()
        );
    }

    @Transactional(noRollbackFor = UploadVerificationException.class)
    public AttachmentResponse completeUpload(UUID tenantId, UUID attachmentId) {
        log.info("Completing attachment upload attachmentId={}", attachmentId);
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        requireNotDeleted(entity);

        if (entity.getStatus() == AttachmentStatus.READY) {
            log.info("Attachment upload was already complete attachmentId={} status={}", attachmentId, entity.getStatus());
            return AttachmentResponse.from(entity, objectMapper);
        }
        if (entity.getStatus() != AttachmentStatus.PENDING_UPLOAD) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Upload cannot be completed",
                    "Only pending uploads can be completed. Current status: " + entity.getStatus()
            );
        }

        assertStorageLocation(entity, tenantId);
        S3StorageService.UploadedObject uploaded = storageService.headObject(
                entity.getS3Key(),
                entity.getChecksumSha256Base64() != null
        );

        log.debug(
                "Comparing uploaded object with pending attachment attachmentId={} declaredSizeBytes={} actualSizeBytes={} expectedContentType={} actualContentType={} checksumExpected={} checksumReturned={}",
                attachmentId,
                entity.getDeclaredSizeBytes(),
                uploaded.sizeBytes(),
                entity.getContentType(),
                uploaded.contentType(),
                entity.getChecksumSha256Base64() != null,
                uploaded.checksumSha256Base64() != null
        );
        verifyUploadedObject(entity, uploaded);
        if (isPlainText(entity.getContentType())) {
            try {
                validateUtf8(storageService.readObject(entity.getS3Key()));
            } catch (ApiException exception) {
                failVerification(entity, exception.getMessage());
            }
        }
        entity.markReady(uploaded.sizeBytes(), uploaded.etag());
        log.info(
                "Attachment upload completed attachmentId={} status={} sizeBytes={} etagPresent={}",
                attachmentId,
                entity.getStatus(),
                uploaded.sizeBytes(),
                uploaded.etag() != null
        );
        return AttachmentResponse.from(entity, objectMapper);
    }

    @Transactional(readOnly = true)
    public AttachmentResponse get(UUID tenantId, UUID attachmentId, boolean includeDeleted) {
        log.debug("Loading attachment attachmentId={} includeDeleted={}", attachmentId, includeDeleted);
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        if (!includeDeleted) {
            requireNotDeleted(entity);
        }
        return AttachmentResponse.from(entity, objectMapper);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttachmentResponse> list(
            UUID tenantId,
            UUID contactId,
            String search,
            AttachmentCategory category,
            AttachmentFileKind fileKind,
            AttachmentStatus status,
            boolean unassigned,
            boolean includeDeleted,
            boolean deletedOnly,
            AttachmentSortField sortBy,
            Sort.Direction sortDirection,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        AttachmentSortField safeSortBy = sortBy == null ? AttachmentSortField.CREATED_AT : sortBy;
        Sort.Direction safeDirection = sortDirection == null ? Sort.Direction.DESC : sortDirection;
        String searchTerm = blankToNull(search);
        log.debug(
                "Listing attachments contactId={} searchPresent={} category={} fileKind={} status={} unassigned={} includeDeleted={} deletedOnly={} sortBy={} sortDirection={} page={} size={}",
                contactId,
                searchTerm != null,
                category,
                fileKind,
                status,
                unassigned,
                includeDeleted,
                deletedOnly,
                safeSortBy,
                safeDirection,
                safePage,
                safeSize
        );

        Specification<AttachmentEntity> specification = tenantSpecification(tenantId);
        if (contactId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("contactId"), contactId));
        } else if (unassigned) {
            specification = specification.and((root, query, cb) -> cb.isNull(root.get("contactId")));
        }
        if (searchTerm != null) {
            String like = "%" + searchTerm.toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("displayName")), like),
                    cb.like(cb.lower(root.get("originalFileName")), like),
                    cb.like(cb.lower(root.get("contentType")), like),
                    cb.like(cb.lower(root.get("sourceSystem")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like)
            ));
        }
        if (category != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        if (fileKind != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("fileKind"), fileKind));
        }
        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (deletedOnly) {
            specification = specification.and((root, query, cb) -> cb.isNotNull(root.get("deletedAt")));
        } else if (!includeDeleted) {
            specification = specification.and((root, query, cb) -> cb.isNull(root.get("deletedAt")));
        }

        Sort sort = Sort.by(safeDirection, safeSortBy.property())
                .and(Sort.by(Sort.Direction.ASC, "id"));
        Page<AttachmentResponse> responsePage = repository.findAll(
                        specification,
                        PageRequest.of(safePage, safeSize, sort)
                )
                .map(entity -> AttachmentResponse.from(entity, objectMapper));
        log.info(
                "Attachments listed contactId={} searchPresent={} unassigned={} includeDeleted={} deletedOnly={} returned={} total={} page={} totalPages={}",
                contactId,
                searchTerm != null,
                unassigned,
                includeDeleted,
                deletedOnly,
                responsePage.getNumberOfElements(),
                responsePage.getTotalElements(),
                responsePage.getNumber(),
                responsePage.getTotalPages()
        );
        return PageResponse.from(responsePage);
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse createDownloadUrl(UUID tenantId, UUID attachmentId, boolean download) {
        log.info("Creating attachment read URL attachmentId={} mode={}", attachmentId, download ? "download" : "inline");
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        requireNotDeleted(entity);

        if (entity.getStatus() != AttachmentStatus.READY && entity.getStatus() != AttachmentStatus.UPLOADED) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "File is not ready",
                    "A download URL is only available after the upload has been verified."
            );
        }

        assertStorageLocation(entity, tenantId);
        String downloadFileName = DownloadFileNameBuilder.build(
                entity.getDisplayName(),
                entity.getOriginalFileName(),
                entity.getContentType()
        );
        S3StorageService.PresignedDownload presigned = storageService.presignDownload(
                entity.getS3Key(),
                downloadFileName,
                entity.getContentType(),
                download
        );
        log.info(
                "Attachment read URL created attachmentId={} mode={} fileName={} expiresAt={}",
                attachmentId,
                download ? "download" : "inline",
                downloadFileName,
                presigned.expiresAt()
        );
        return new DownloadUrlResponse(entity.getId(), presigned.url(), presigned.expiresAt());
    }

    @Transactional(readOnly = true)
    public byte[] readTextContent(UUID tenantId, UUID attachmentId) {
        log.info("Reading UTF-8 text attachment attachmentId={}", attachmentId);
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        requireNotDeleted(entity);

        if (entity.getStatus() != AttachmentStatus.READY && entity.getStatus() != AttachmentStatus.UPLOADED) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "File is not ready",
                    "Text content is only available after the upload has been verified."
            );
        }
        if (!isPlainText(entity.getContentType())) {
            throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File is not plain text",
                    "Only UTF-8 text/plain attachments can be opened in the text viewer."
            );
        }

        long sizeBytes = entity.getActualSizeBytes() == null
                ? entity.getDeclaredSizeBytes()
                : entity.getActualSizeBytes();
        validateTextFileSize(entity.getContentType(), sizeBytes);
        assertStorageLocation(entity, tenantId);

        byte[] bytes = storageService.readObject(entity.getS3Key());
        validateUtf8(bytes);
        return stripUtf8Bom(bytes);
    }

    @Transactional
    public AttachmentResponse softDelete(UUID tenantId, UUID attachmentId) {
        log.info("Soft deleting attachment attachmentId={}", attachmentId);
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        entity.softDelete();
        log.info("Attachment soft deleted attachmentId={} deletedAt={}", attachmentId, entity.getDeletedAt());
        return AttachmentResponse.from(entity, objectMapper);
    }

    @Transactional
    public void permanentlyDelete(UUID tenantId, UUID attachmentId) {
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        if (entity.getDeletedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Attachment is not in Trash",
                    "Move the attachment to Trash before permanently deleting it.");
        }
        if (repository.existsByParentAttachmentId(attachmentId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Attachment has linked files",
                    "Permanently delete its linked files first.");
        }

        assertStorageLocation(entity, tenantId);
        // Delete S3 first. If S3 fails, the database row remains in Trash; if the
        // database fails later, retrying is safe because S3 deletion is idempotent.
        storageService.deleteObject(entity.getS3Key());
        repository.delete(entity);
        repository.flush();
        log.info("Attachment permanently deleted attachmentId={}", attachmentId);
    }

    @Transactional
    public AttachmentResponse restore(UUID tenantId, UUID attachmentId) {
        log.info("Restoring attachment attachmentId={}", attachmentId);
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        entity.restore();
        log.info("Attachment restored attachmentId={} status={}", attachmentId, entity.getStatus());
        return AttachmentResponse.from(entity, objectMapper);
    }

    @Transactional
    public AttachmentResponse assignToContact(UUID tenantId, UUID attachmentId, UUID contactId) {
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        requireNotDeleted(entity);
        UUID previousContactId = entity.getContactId();
        log.info(
                "Changing attachment assignment attachmentId={} previousContactId={} requestedContactId={}",
                attachmentId,
                previousContactId,
                contactId
        );
        validateRelatedRecords(tenantId, contactId, null, null, "MANUAL");
        entity.assignContact(contactId);
        log.info(
                "Attachment assignment changed attachmentId={} previousContactId={} contactId={} action={}",
                attachmentId,
                previousContactId,
                entity.getContactId(),
                contactId == null ? "UNASSIGN" : previousContactId == null ? "ASSIGN" : "REASSIGN"
        );
        return AttachmentResponse.from(entity, objectMapper);
    }

    @Transactional
    public AttachmentResponse rename(UUID tenantId, UUID attachmentId, String displayName) {
        AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
        requireNotDeleted(entity);
        String name = displayName == null ? "" : displayName.trim();
        if (name.isBlank() || name.length() > 255 || name.chars().anyMatch(c -> c == '\n' || c == '\r')) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid display name",
                    "Enter a display name of up to 255 characters on one line.");
        }
        entity.rename(name);
        log.info("Attachment renamed attachmentId={}", attachmentId);
        return AttachmentResponse.from(entity, objectMapper);
    }

    @Transactional
    public BatchUpdateAttachmentsResponse batchUpdate(
            UUID tenantId,
            BatchUpdateAttachmentsRequest request
    ) {
        if (!request.updateContact() && request.category() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "No batch update selected",
                    "Choose a client assignment, a category, or both."
            );
        }

        Set<UUID> uniqueAttachmentIds = new LinkedHashSet<>(request.attachmentIds());
        if (uniqueAttachmentIds.contains(null)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid attachment selection",
                    "Attachment IDs cannot be null."
            );
        }

        if (request.updateContact()) {
            validateRelatedRecords(tenantId, request.contactId(), null, null, "MANUAL");
        }

        List<AttachmentResponse> updated = new ArrayList<>(uniqueAttachmentIds.size());
        for (UUID attachmentId : uniqueAttachmentIds) {
            AttachmentEntity entity = requireAttachment(tenantId, attachmentId);
            requireNotDeleted(entity);

            if (request.category() != null) {
                validateCategory(request.category(), entity.getFileKind());
                entity.changeCategory(request.category());
            }
            if (request.updateContact()) {
                entity.assignContact(request.contactId());
            }
            updated.add(AttachmentResponse.from(entity, objectMapper));
        }

        log.info(
                "Batch attachment update completed requestedCount={} updatedCount={} updateContact={} contactId={} category={}",
                request.attachmentIds().size(),
                updated.size(),
                request.updateContact(),
                request.contactId(),
                request.category()
        );
        return new BatchUpdateAttachmentsResponse(updated.size(), List.copyOf(updated));
    }

    private AttachmentEntity requireAttachment(UUID tenantId, UUID attachmentId) {
        return repository.findByIdAndTenantId(attachmentId, tenantId)
                .orElseThrow(() -> {
                    log.warn("Attachment lookup failed attachmentId={}", attachmentId);
                    return new ApiException(HttpStatus.NOT_FOUND, "Attachment not found", "No attachment was found for this tenant.");
                });
    }

    private void requireNotDeleted(AttachmentEntity entity) {
        if (entity.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Attachment not found", "The attachment has been deleted.");
        }
    }

    private Specification<AttachmentEntity> tenantSpecification(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    private void validateRelatedRecords(
            UUID tenantId,
            UUID contactId,
            UUID estimateId,
            UUID parentAttachmentId,
            String sourceSystem
    ) {
        if (contactId != null
                && !contactService.belongsToTenant(tenantId, contactId)
                && !securityProperties.isAllowUnverifiedContactIds()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Contact not found",
                    "The selected contact does not exist for this tenant."
            );
        }
        if (estimateId != null
                && !"ESTIMATOR".equals(sourceSystem)
                && !securityProperties.isAllowUnverifiedEstimateIds()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Estimate verification is not configured",
                    "Only authenticated Estimator uploads may supply an estimate ID until shared estimate verification is available."
            );
        }
        if (parentAttachmentId != null) {
            AttachmentEntity parent = requireAttachment(tenantId, parentAttachmentId);
            requireNotDeleted(parent);
            if (parent.getStatus() != AttachmentStatus.READY && parent.getStatus() != AttachmentStatus.UPLOADED) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "Parent attachment is not ready",
                        "A derivative can only be linked to an uploaded parent attachment."
                );
            }
        }
    }

    private void assertStorageLocation(AttachmentEntity entity, UUID tenantId) {
        if (!Objects.equals(entity.getS3Bucket(), storageService.bucket())) {
            throw new IllegalStateException("Stored attachment bucket does not match the configured Client Files bucket.");
        }
        storageService.assertKeyBelongsToTenant(entity.getS3Key(), tenantId);
    }

    private void validateContentType(String contentType) {
        if (!storageService.isAllowedContentType(contentType)) {
            throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported file type",
                    "The content type is not allowed: " + contentType
            );
        }
    }

    private void validateTextFileSize(String contentType, long sizeBytes) {
        if (isPlainText(contentType) && sizeBytes > storageService.maxTextFileSizeBytes()) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Text file is too large",
                    "Plain-text files are limited to " + storageService.maxTextFileSizeBytes()
                            + " bytes so they can be validated and displayed safely."
            );
        }
    }

    private boolean isPlainText(String contentType) {
        return "text/plain".equals(storageService.normalizeContentType(contentType));
    }

    private void validateUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Text file is not valid UTF-8",
                    "Save the file as UTF-8 plain text before uploading it. This prevents bullets, em dashes, tildes, and other characters from displaying incorrectly."
            );
        }
    }

    private byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            byte[] withoutBom = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, withoutBom, 0, withoutBom.length);
            return withoutBom;
        }
        return bytes;
    }

    private void validateFileSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid file size", "File size must be greater than zero.");
        }
        if (sizeBytes > storageService.maxFileSizeBytes()) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "File is too large",
                    "The file exceeds the configured maximum size of " + storageService.maxFileSizeBytes() + " bytes."
            );
        }
    }

    private AttachmentFileKind inferFileKind(String contentType) {
        if (contentType.startsWith("image/")) {
            return AttachmentFileKind.IMAGE;
        }
        if (contentType.startsWith("video/")) {
            return AttachmentFileKind.VIDEO;
        }
        if (contentType.startsWith("application/") || contentType.startsWith("text/")) {
            return AttachmentFileKind.DOCUMENT;
        }
        return AttachmentFileKind.OTHER;
    }

    private void validateCategory(AttachmentCategory category, AttachmentFileKind fileKind) {
        boolean valid = switch (category) {
            case PROPERTY_PHOTOS -> fileKind == AttachmentFileKind.IMAGE;
            case VIDEOS -> fileKind == AttachmentFileKind.VIDEO;
            case QUOTES -> fileKind == AttachmentFileKind.DOCUMENT || fileKind == AttachmentFileKind.IMAGE;
            case ESTIMATES, DOCUMENTS -> fileKind == AttachmentFileKind.DOCUMENT;
            case OTHER -> true;
        };

        if (!valid) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "File does not match category",
                    "The selected category is not compatible with the supplied content type."
            );
        }
    }

    private void verifyUploadedObject(AttachmentEntity entity, S3StorageService.UploadedObject uploaded) {
        if (uploaded.sizeBytes() != entity.getDeclaredSizeBytes()) {
            failVerification(entity, "Uploaded size does not match the declared size.");
        }
        if (uploaded.sizeBytes() > storageService.maxFileSizeBytes()) {
            failVerification(entity, "Uploaded file exceeds the configured size limit.");
        }
        if (!Objects.equals(uploaded.contentType(), entity.getContentType())) {
            failVerification(entity, "Uploaded content type does not match the initialized upload.");
        }
        if (entity.getChecksumSha256Base64() != null
                && !Objects.equals(entity.getChecksumSha256Base64(), uploaded.checksumSha256Base64())) {
            failVerification(entity, "Uploaded SHA-256 checksum does not match.");
        }
    }

    private void failVerification(AttachmentEntity entity, String reason) {
        log.warn("Attachment upload verification failed attachmentId={} reason={}", entity.getId(), reason);
        entity.markFailed(reason);
        repository.save(entity);
        throw new UploadVerificationException(reason);
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid metadata", "Metadata could not be serialized as JSON.");
        }
    }

    private String normalizeSourceSystem(String value) {
        if (value == null || value.isBlank()) {
            return "MANUAL";
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("BASE44") || normalized.equals("CLIENT_FILES") || normalized.equals("CLIENT-FILES")) {
            return "MANUAL";
        }

        return switch (normalized) {
            case "QUO", "FACEBOOK", "JOBBER", "WEBSITE", "GMAIL", "MANUAL", "ESTIMATOR" -> normalized;
            default -> throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported source system",
                    "Use one of QUO, FACEBOOK, JOBBER, WEBSITE, GMAIL, MANUAL, or ESTIMATOR."
            );
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

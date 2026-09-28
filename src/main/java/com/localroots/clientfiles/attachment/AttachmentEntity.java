package com.localroots.clientfiles.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "contact_attachments")
public class AttachmentEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "estimate_id")
    private UUID estimateId;

    @Column(name = "parent_attachment_id")
    private UUID parentAttachmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttachmentCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_kind", nullable = false, length = 32)
    private AttachmentFileKind fileKind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttachmentStatus status;

    @Column(name = "file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "content_type", nullable = false, length = 255)
    private String contentType;

    @Column(name = "file_size")
    private Long declaredSizeBytes;

    @Column(name = "actual_size_bytes")
    private Long actualSizeBytes;

    @Column(name = "checksum_sha256_base64", length = 128)
    private String checksumSha256Base64;

    @Column(name = "s3_bucket", nullable = false, length = 255)
    private String s3Bucket;

    @Column(name = "s3_key", nullable = false, length = 1024)
    private String s3Key;

    @Column(name = "etag", length = 255)
    private String etag;

    @Column(name = "source_system", nullable = false, length = 255)
    private String sourceSystem;

    @Column(length = 2000)
    private String description;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "uploaded_at")
    private OffsetDateTime uploadedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected AttachmentEntity() {
    }

    public static AttachmentEntity pending(
            UUID id,
            UUID tenantId,
            UUID contactId,
            UUID estimateId,
            UUID parentAttachmentId,
            AttachmentCategory category,
            AttachmentFileKind fileKind,
            String originalFileName,
            String displayName,
            String contentType,
            long declaredSizeBytes,
            String checksumSha256Base64,
            String s3Bucket,
            String s3Key,
            String sourceSystem,
            String description,
            String metadataJson,
            UUID createdByUserId
    ) {
        AttachmentEntity attachment = new AttachmentEntity();
        attachment.id = id;
        attachment.tenantId = tenantId;
        attachment.contactId = contactId;
        attachment.estimateId = estimateId;
        attachment.parentAttachmentId = parentAttachmentId;
        attachment.category = category;
        attachment.fileKind = fileKind;
        attachment.status = AttachmentStatus.PENDING_UPLOAD;
        attachment.originalFileName = originalFileName;
        attachment.displayName = displayName;
        attachment.contentType = contentType;
        attachment.declaredSizeBytes = declaredSizeBytes;
        attachment.checksumSha256Base64 = checksumSha256Base64;
        attachment.s3Bucket = s3Bucket;
        attachment.s3Key = s3Key;
        attachment.sourceSystem = sourceSystem;
        attachment.description = description;
        attachment.metadataJson = metadataJson;
        attachment.createdByUserId = createdByUserId;
        return attachment;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void markReady(long sizeBytes, String etag) {
        this.status = AttachmentStatus.READY;
        this.actualSizeBytes = sizeBytes;
        this.etag = etag;
        this.uploadedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = AttachmentStatus.FAILED;
        this.failureReason = reason;
    }

    public void softDelete() {
        if (deletedAt == null) {
            deletedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public void restore() {
        deletedAt = null;
    }

    public void assignContact(UUID contactId) {
        this.contactId = contactId;
    }

    public void changeCategory(AttachmentCategory category) {
        this.category = category;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getContactId() { return contactId; }
    public UUID getEstimateId() { return estimateId; }
    public UUID getParentAttachmentId() { return parentAttachmentId; }
    public AttachmentCategory getCategory() { return category; }
    public AttachmentFileKind getFileKind() { return fileKind; }
    public AttachmentStatus getStatus() { return status; }
    public String getOriginalFileName() { return originalFileName; }
    public String getDisplayName() { return displayName; }
    public String getContentType() { return contentType; }
    public long getDeclaredSizeBytes() { return declaredSizeBytes == null ? 0L : declaredSizeBytes; }
    public Long getActualSizeBytes() { return actualSizeBytes; }
    public String getChecksumSha256Base64() { return checksumSha256Base64; }
    public String getS3Bucket() { return s3Bucket; }
    public String getS3Key() { return s3Key; }
    public String getEtag() { return etag; }
    public String getSourceSystem() { return sourceSystem; }
    public String getDescription() { return description; }
    public String getMetadataJson() { return metadataJson; }
    public String getFailureReason() { return failureReason; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getUploadedAt() { return uploadedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public long getRowVersion() { return rowVersion; }
}

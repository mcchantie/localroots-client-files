package com.localroots.clientfiles.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<AttachmentEntity, UUID>, JpaSpecificationExecutor<AttachmentEntity> {

    Optional<AttachmentEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from AttachmentEntity a where a.id=:id and a.tenantId=:tenantId")
    Optional<AttachmentEntity> lockForEstimateQuote(@org.springframework.data.repository.query.Param("id") UUID id,
        @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);

    boolean existsByParentAttachmentId(UUID parentAttachmentId);
}

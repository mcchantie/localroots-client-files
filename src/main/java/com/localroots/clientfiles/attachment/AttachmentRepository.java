package com.localroots.clientfiles.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<AttachmentEntity, UUID>, JpaSpecificationExecutor<AttachmentEntity> {

    Optional<AttachmentEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByParentAttachmentId(UUID parentAttachmentId);
}

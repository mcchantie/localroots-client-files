package com.localroots.clientfiles.attachment;

import com.localroots.clientfiles.api.AttachmentResponse;
import com.localroots.clientfiles.common.ApiException;
import com.localroots.clientfiles.contact.ContactService;
import com.localroots.clientfiles.security.ClientFilesSecurityProperties;
import com.localroots.clientfiles.storage.S3StorageService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentRenameTest {

    @Test
    void renamesDisplayNameWithoutChangingOriginalFileOrStorageKey() {
        UUID tenantId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String key = "tenants/" + tenantId + "/original.pdf";
        AttachmentEntity entity = attachment(tenantId, attachmentId, key);
        AttachmentRepository repository = mock(AttachmentRepository.class);
        when(repository.findByIdAndTenantId(attachmentId, tenantId)).thenReturn(Optional.of(entity));

        AttachmentService service = service(repository);
        AttachmentResponse response = service.rename(tenantId, attachmentId, "  Quote for Smith  ");

        assertEquals("Quote for Smith", response.displayName());
        assertEquals("scan.pdf", entity.getOriginalFileName());
        assertEquals(key, entity.getS3Key());
        assertThrows(ApiException.class, () -> service.rename(tenantId, attachmentId, "  "));
    }

    @Test
    void cannotRenameAnotherTenantsAttachment() {
        AttachmentService service = service(mock(AttachmentRepository.class));
        assertThrows(ApiException.class,
                () -> service.rename(UUID.randomUUID(), UUID.randomUUID(), "Quote"));
    }

    private AttachmentService service(AttachmentRepository repository) {
        return new AttachmentService(repository, mock(S3StorageService.class),
                mock(ClientFilesSecurityProperties.class), mock(ContactService.class), new ObjectMapper());
    }

    private AttachmentEntity attachment(UUID tenantId, UUID attachmentId, String key) {
        return AttachmentEntity.pending(attachmentId, tenantId, null, null, null,
                AttachmentCategory.QUOTES, AttachmentFileKind.DOCUMENT, "scan.pdf", "scan.pdf",
                "application/pdf", 10L, null, "bucket", key, "MANUAL", null, null, null);
    }
}

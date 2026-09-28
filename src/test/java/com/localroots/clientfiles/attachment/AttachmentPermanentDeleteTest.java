package com.localroots.clientfiles.attachment;

import com.localroots.clientfiles.common.ApiException;
import com.localroots.clientfiles.contact.ContactService;
import com.localroots.clientfiles.security.ClientFilesSecurityProperties;
import com.localroots.clientfiles.storage.S3StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AttachmentPermanentDeleteTest {

    private final AttachmentRepository repository = mock(AttachmentRepository.class);
    private final S3StorageService storage = mock(S3StorageService.class);
    private final AttachmentService service = new AttachmentService(repository, storage,
            mock(ClientFilesSecurityProperties.class), mock(ContactService.class), mock(ObjectMapper.class));
    private final UUID tenantId = UUID.randomUUID();
    private final UUID attachmentId = UUID.randomUUID();
    private final String bucket = "client-files";
    private final String key = "tenants/" + tenantId + "/contacts/unassigned/attachments/" + attachmentId + "/original/documents/file.pdf";

    private AttachmentEntity attachment() {
        AttachmentEntity entity = AttachmentEntity.pending(attachmentId, tenantId, null, null, null,
                AttachmentCategory.DOCUMENTS, AttachmentFileKind.DOCUMENT, "file.pdf", "file.pdf",
                "application/pdf", 10, null, bucket, key, "MANUAL", null, null, null);
        when(repository.findByIdAndTenantId(attachmentId, tenantId)).thenReturn(Optional.of(entity));
        return entity;
    }

    @Test
    void deletesStoredObjectBeforeRemovingTrashedRow() {
        attachment().softDelete();
        when(storage.bucket()).thenReturn(bucket);

        service.permanentlyDelete(tenantId, attachmentId);

        var ordered = inOrder(storage, repository);
        ordered.verify(storage).assertKeyBelongsToTenant(key, tenantId);
        ordered.verify(storage).deleteObject(key);
        ordered.verify(repository).delete(any(AttachmentEntity.class));
        ordered.verify(repository).flush();
    }

    @Test
    void refusesActiveAttachment() {
        attachment();

        ApiException error = assertThrows(ApiException.class,
                () -> service.permanentlyDelete(tenantId, attachmentId));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(storage, never()).deleteObject(anyString());
        verify(repository, never()).delete(any());
    }

    @Test
    void refusesParentWithLinkedFiles() {
        attachment().softDelete();
        when(repository.existsByParentAttachmentId(attachmentId)).thenReturn(true);

        ApiException error = assertThrows(ApiException.class,
                () -> service.permanentlyDelete(tenantId, attachmentId));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(storage, never()).deleteObject(anyString());
    }

    @Test
    void retainsRowIfStorageDeleteFails() {
        attachment().softDelete();
        when(storage.bucket()).thenReturn(bucket);
        doThrow(new IllegalStateException("S3 unavailable")).when(storage).deleteObject(key);

        assertThrows(IllegalStateException.class, () -> service.permanentlyDelete(tenantId, attachmentId));

        verify(repository, never()).delete(any());
    }

    @Test
    void cannotDeleteAnotherTenantsAttachment() {
        assertThrows(ApiException.class, () -> service.permanentlyDelete(tenantId, attachmentId));
        verify(storage, never()).deleteObject(anyString());
    }
}

package com.localroots.clientfiles.api;
import com.localroots.clientfiles.attachment.*;
import com.localroots.clientfiles.common.ApiException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmFileReferenceControllerTest {
    private final AttachmentRepository repository=mock(AttachmentRepository.class);
    private final UUID tenant=UUID.randomUUID(),contact=UUID.randomUUID(),fileId=UUID.randomUUID();
    private final String key="a-dedicated-crm-integration-key-of-at-least-32-characters";
    private CrmFileReferenceController controller(){return new CrmFileReferenceController(repository,key,tenant.toString());}
    @Test void rejectsMissingKeyBeforeLookingUpAnyFile(){
        assertThrows(ApiException.class,()->controller().verify(contact,fileId,null,tenant.toString()));
        verifyNoInteractions(repository);
    }
    @Test void serviceKeyCannotSelectAnUnconfiguredTenant(){
        assertThrows(ApiException.class,()->controller().verify(contact,fileId,key,UUID.randomUUID().toString()));
        verifyNoInteractions(repository);
    }
    @Test void permitsReadyFileOwnedByContact(){
        var file=mock(AttachmentEntity.class);when(file.getContactId()).thenReturn(contact);when(file.getStatus()).thenReturn(AttachmentStatus.READY);
        when(repository.findByIdAndTenantId(fileId,tenant)).thenReturn(Optional.of(file));
        assertDoesNotThrow(()->controller().verify(contact,fileId,key,tenant.toString()));
    }
    @Test void rejectsAnotherContactOrDeletedOrPendingFiles(){
        var file=mock(AttachmentEntity.class);when(repository.findByIdAndTenantId(fileId,tenant)).thenReturn(Optional.of(file));
        when(file.getContactId()).thenReturn(UUID.randomUUID());
        assertThrows(ApiException.class,()->controller().verify(contact,fileId,key,tenant.toString()));
        when(file.getContactId()).thenReturn(contact);when(file.getStatus()).thenReturn(AttachmentStatus.PENDING_UPLOAD);
        assertThrows(ApiException.class,()->controller().verify(contact,fileId,key,tenant.toString()));
        when(file.getStatus()).thenReturn(AttachmentStatus.READY);when(file.getDeletedAt()).thenReturn(java.time.OffsetDateTime.now());
        assertThrows(ApiException.class,()->controller().verify(contact,fileId,key,tenant.toString()));
    }
}

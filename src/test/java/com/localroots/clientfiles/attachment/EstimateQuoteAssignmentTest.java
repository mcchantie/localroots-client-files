package com.localroots.clientfiles.attachment;

import com.localroots.clientfiles.common.ApiException;
import com.localroots.clientfiles.contact.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EstimateQuoteAssignmentTest {
    final UUID tenant=UUID.randomUUID(), id=UUID.randomUUID(), estimate=UUID.randomUUID(), contact=UUID.randomUUID();
    final AttachmentRepository repo=mock(AttachmentRepository.class);
    final ContactService contacts=mock(ContactService.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final EstimateQuoteAssignmentService service=new EstimateQuoteAssignmentService(repo,contacts,jdbc);
    EstimateQuoteAssignmentService.Request request() {return new EstimateQuoteAssignmentService.Request(estimate,"Mary Ann","De La Cruz",null,null);}
    AttachmentEntity attachment(UUID owner) {
        var a=AttachmentEntity.pending(id,tenant,owner,estimate,null,AttachmentCategory.ESTIMATES,AttachmentFileKind.DOCUMENT,
            "estimate.txt","Estimate","text/plain",10,null,"bucket","original-key","ESTIMATOR",null,null,null);
        a.markReady(10L,null);return a;
    }
    @SuppressWarnings("unchecked") void prior(List<UUID> ids) {when(jdbc.query(anyString(),any(RowMapper.class),any(),any())).thenReturn(ids);}
    ContactResponse response() {return new ContactResponse(contact,"Old","Name","Old Name","Old Name","7135550100","old@test.com","Keep these notes",null,null,0);}
    @Test void unassignedEstimateCreatesContactAndMovesTheSameAttachment() {
        var a=attachment(null);when(repo.lockForEstimateQuote(id,tenant)).thenReturn(Optional.of(a));prior(List.of());
        when(contacts.createFromEstimateQuote(eq(tenant),any())).thenReturn(response());
        assertEquals(contact,service.assign(tenant,id,request()).contactId());assertEquals(contact,a.getContactId());assertEquals("original-key",a.getS3Key());
        verify(contacts).createFromEstimateQuote(tenant,new ContactRequest("Mary Ann","De La Cruz","Mary Ann De La Cruz",null,null,"Created from a formal quote request for estimate "+estimate));
        verify(jdbc).update(anyString(),eq(tenant),eq(id),eq(estimate),eq(contact));
    }
    @Test void repeatedDeliveryReturnsTheRecordedContactWithoutCreatingAnother() {
        when(repo.lockForEstimateQuote(id,tenant)).thenReturn(Optional.of(attachment(contact)));prior(List.of(contact));
        assertEquals(contact,service.assign(tenant,id,request()).contactId());verifyNoInteractions(contacts);
    }
    @Test void assignedEstimateKeepsItsContactAndUpdatesExactNamesWithoutLosingContactDetails() {
        var a=attachment(contact);when(repo.lockForEstimateQuote(id,tenant)).thenReturn(Optional.of(a));prior(List.of());when(contacts.get(tenant,contact)).thenReturn(response());
        assertEquals(contact,service.assign(tenant,id,request()).contactId());
        verify(contacts).update(tenant,contact,new ContactRequest("Mary Ann","De La Cruz","Mary Ann De La Cruz","7135550100","old@test.com","Keep these notes"));
        verify(contacts,never()).createFromEstimateQuote(any(),any());
    }
    @Test void otherTenantAndMismatchedEstimateCannotAssignAFile() {
        when(repo.lockForEstimateQuote(id,tenant)).thenReturn(Optional.empty());assertThrows(ApiException.class,()->service.assign(tenant,id,request()));
        when(repo.lockForEstimateQuote(id,tenant)).thenReturn(Optional.of(attachment(null)));
        assertThrows(ApiException.class,()->service.assign(tenant,id,new EstimateQuoteAssignmentService.Request(UUID.randomUUID(),"Mary","Smith",null,null)));
        var deleted=attachment(null);deleted.softDelete();when(repo.lockForEstimateQuote(id,tenant)).thenReturn(Optional.of(deleted));
        assertThrows(ApiException.class,()->service.assign(tenant,id,request()));
        verifyNoInteractions(contacts,jdbc);
    }
    @Test void assignedContactReceivesSubmittedContactMethodAndKeepsOtherDetails() {
        when(repo.lockForEstimateQuote(id,tenant)).thenReturn(Optional.of(attachment(contact)));prior(List.of());when(contacts.get(tenant,contact)).thenReturn(response());
        service.assign(tenant,id,new EstimateQuoteAssignmentService.Request(estimate,"Testing","Testing","7135550123",null));
        verify(contacts).update(tenant,contact,new ContactRequest("Testing","Testing","Testing Testing","7135550123","old@test.com","Keep these notes"));
    }
}

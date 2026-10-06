package com.localroots.clientfiles.contact;
import org.junit.jupiter.api.Test;
import com.localroots.clientfiles.common.ApiException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class EstimateQuoteContactTest {
    @Test void createsNameOnlyContactFromExactQuoteNameParts() {
        var repo=mock(ContactRepository.class);
        when(repo.save(any())).thenAnswer(call->call.getArgument(0));
        var service=new ContactService(repo);
        var response=service.createFromEstimateQuote(UUID.randomUUID(),new ContactRequest("Mary Ann","De La Cruz",null,null,null,null));
        assertEquals("Mary Ann",response.firstName());assertEquals("De La Cruz",response.lastName());assertNull(response.phone());assertNull(response.email());
        assertThrows(ApiException.class,()->service.createFromEstimateQuote(UUID.randomUUID(),new ContactRequest(" ",null,null,null,null,null)));
    }
}

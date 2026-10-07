package com.localroots.clientfiles.contact;

import com.localroots.clientfiles.attachment.AttachmentService;
import com.localroots.clientfiles.security.RequestTenantResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContactSearchHttpTest {
    @Test void uiQueryParameterFiltersContactsWithinAuthenticatedTenant() throws Exception {
        var contacts=mock(ContactService.class);var resolver=mock(RequestTenantResolver.class);var tenant=UUID.randomUUID();
        when(resolver.requireTenantId(any())).thenReturn(tenant);
        var mvc=MockMvcBuilders.standaloneSetup(new ContactController(contacts,mock(AttachmentService.class),resolver)).build();
        mvc.perform(get("/api/v1/contacts").param("q","Testing")).andExpect(status().isOk());
        verify(contacts).list(tenant,"Testing",0,50);
        mvc.perform(get("/api/v1/contacts").param("search","Tester").param("q","ignored").param("page","1")).andExpect(status().isOk());
        verify(contacts).list(tenant,"Tester",1,50);
    }
}

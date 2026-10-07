package com.localroots.clientfiles.attachment;

import com.localroots.clientfiles.common.ApiException;
import com.localroots.clientfiles.contact.*;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class EstimateQuoteAssignmentService {
    private final AttachmentRepository attachments;
    private final ContactService contacts;
    private final JdbcTemplate jdbc;
    public EstimateQuoteAssignmentService(AttachmentRepository attachments,ContactService contacts,JdbcTemplate jdbc) {
        this.attachments=attachments;this.contacts=contacts;this.jdbc=jdbc;
    }
    public record Request(@NotNull UUID estimateId,@NotBlank @Size(max=100) String firstName,
        @Size(max=100) String lastName,@Size(max=100) String phone,@Email @Size(max=254) String email) {}
    public record Response(UUID contactId) {}
    @Transactional
    public Response assign(UUID tenantId,UUID attachmentId,Request request) {
        // Serialize contact creation for this attachment, including concurrent or repeated deliveries.
        var attachment=attachments.lockForEstimateQuote(attachmentId,tenantId)
            .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Estimate not found","No estimate was found for this tenant."));
        if(attachment.getDeletedAt()!=null || attachment.getStatus()!=AttachmentStatus.READY || attachment.getCategory()!=AttachmentCategory.ESTIMATES
            || !"ESTIMATOR".equals(attachment.getSourceSystem()) || !request.estimateId().equals(attachment.getEstimateId()))
            throw new ApiException(HttpStatus.CONFLICT,"Estimate mismatch","This is not the saved estimator attachment.");
        var prior=jdbc.query("select contact_id from estimator_quote_contact_links where tenant_id=? and attachment_id=?",
            (r,n)->r.getObject(1,UUID.class),tenantId,attachmentId);
        if(!prior.isEmpty()) return new Response(prior.get(0));
        UUID contactId=attachment.getContactId();
        String first=request.firstName().trim(),last=request.lastName()==null?null:request.lastName().trim();
        String display=(first+" "+(last==null?"":last)).trim();
        if(contactId==null) {
            contactId=contacts.createFromEstimateQuote(tenantId,new ContactRequest(first,last,display,
                request.phone(),request.email(),"Created from a formal quote request for estimate "+request.estimateId())).id();
        } else {
            var existing=contacts.get(tenantId,contactId);
            contacts.update(tenantId,contactId,new ContactRequest(first,last,display,
                request.phone()==null || request.phone().isBlank()?existing.phone():request.phone(),
                request.email()==null || request.email().isBlank()?existing.email():request.email(),existing.notes()));
        }
        attachment.assignContact(contactId);
        jdbc.update("insert into estimator_quote_contact_links(tenant_id,attachment_id,estimate_id,contact_id) values(?,?,?,?)",
            tenantId,attachmentId,request.estimateId(),contactId);
        return new Response(contactId);
    }
}

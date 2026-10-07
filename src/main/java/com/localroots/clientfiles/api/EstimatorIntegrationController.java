package com.localroots.clientfiles.api;

import com.localroots.clientfiles.attachment.EstimateQuoteAssignmentService;
import com.localroots.clientfiles.common.ApiException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Server-to-server entry point. Public quote callers never receive the integration key. */
@RestController
@RequestMapping("/api/internal/estimator/attachments")
public class EstimatorIntegrationController {
    private final EstimateQuoteAssignmentService assignments;
    private final String apiKey;
    public EstimatorIntegrationController(EstimateQuoteAssignmentService assignments,
        @Value("${CLIENT_FILES_ESTIMATOR_API_KEY:}") String apiKey) {
        this.assignments=assignments;this.apiKey=apiKey;
    }
    @PostMapping("/{attachmentId}/quote-request")
    public EstimateQuoteAssignmentService.Response assignQuote(@PathVariable UUID attachmentId,
        @RequestHeader(value="X-Estimator-Key",required=false) String suppliedKey,
        @RequestHeader(value="X-Estimator-Tenant",required=false) String savedTenant,
        @Valid @RequestBody EstimateQuoteAssignmentService.Request body) {
        if(apiKey.length()<32) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Integration is not configured","Configure the Client Files Estimator integration key.");
        if(suppliedKey==null || !MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8),suppliedKey.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED,"Invalid integration credentials","A valid Estimator service key is required.");
        UUID tenant;
        try {tenant=UUID.fromString(savedTenant);} catch(IllegalArgumentException | NullPointerException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"Tenant is required","Send the tenant saved on the estimate.");
        }
        // The service locks the attachment using BOTH its ID and this tenant, then checks its estimate ID.
        return assignments.assign(tenant,attachmentId,body);
    }
}

package com.localroots.clientfiles.contact;

import com.localroots.clientfiles.api.PageResponse;
import com.localroots.clientfiles.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final ContactRepository repository;

    public ContactService(ContactRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ContactResponse create(UUID tenantId, ContactRequest request) {
        return createInternal(tenantId,request,false);
    }

    @Transactional
    public ContactResponse createFromEstimateQuote(UUID tenantId, ContactRequest request) {
        if (!hasText(request.firstName())) throw new ApiException(HttpStatus.BAD_REQUEST,"Name is required","Enter the customer's first name.");
        return createInternal(tenantId,request,true);
    }

    private ContactResponse createInternal(UUID tenantId, ContactRequest request, boolean allowNameOnly) {
        log.info(
                "Creating contact namePresent={} phonePresent={} emailPresent={}",
                hasAnyName(request),
                hasText(request.phone()),
                hasText(request.email())
        );

        Values values = validateAndNormalize(request,allowNameOnly);
        rejectDuplicates(tenantId, values, null);

        ContactEntity entity = ContactEntity.create(
                UUID.randomUUID(),
                tenantId,
                values.firstName(),
                values.lastName(),
                values.displayName(),
                values.phone(),
                values.normalizedPhone(),
                values.email(),
                values.normalizedEmail(),
                values.notes()
        );
        ContactResponse response = ContactResponse.from(repository.save(entity));
        log.info(
                "Contact created contactId={} displayNamePresent={} phonePresent={} emailPresent={}",
                response.id(),
                values.displayName() != null,
                values.normalizedPhone() != null,
                values.normalizedEmail() != null
        );
        return response;
    }

    @Transactional
    public ContactResponse update(UUID tenantId, UUID contactId, ContactRequest request) {
        log.info(
                "Updating contact contactId={} namePresent={} phonePresent={} emailPresent={}",
                contactId,
                hasAnyName(request),
                hasText(request.phone()),
                hasText(request.email())
        );

        ContactEntity entity = requireContact(tenantId, contactId);
        Values values = validateAndNormalize(request,true);
        rejectDuplicates(tenantId, values, entity);
        entity.update(
                values.firstName(),
                values.lastName(),
                values.displayName(),
                values.phone(),
                values.normalizedPhone(),
                values.email(),
                values.normalizedEmail(),
                values.notes()
        );
        ContactResponse response = ContactResponse.from(entity);
        log.info("Contact updated contactId={}", contactId);
        return response;
    }

    @Transactional(readOnly = true)
    public ContactResponse get(UUID tenantId, UUID contactId) {
        log.debug("Loading contact contactId={}", contactId);
        return ContactResponse.from(requireContact(tenantId, contactId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ContactResponse> list(UUID tenantId, String search, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String term = blankToNull(search);

        log.debug(
                "Listing contacts searchPresent={} searchLength={} page={} size={}",
                term != null,
                term == null ? 0 : term.length(),
                safePage,
                safeSize
        );

        Specification<ContactEntity> specification = (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);

        if (term != null) {
            String like = "%" + term.toLowerCase(Locale.ROOT) + "%";
            String phoneDigits = normalizePhone(term);
            specification = specification.and((root, query, cb) -> {
                var textMatch = cb.or(
                        cb.like(cb.lower(root.get("displayName")), like),
                        cb.like(cb.lower(root.get("firstName")), like),
                        cb.like(cb.lower(root.get("lastName")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("phone")), like)
                );
                if (phoneDigits == null) {
                    return textMatch;
                }
                return cb.or(textMatch, cb.like(root.get("normalizedPhone"), "%" + phoneDigits + "%"));
            });
        }

        Page<ContactResponse> response = repository.findAll(
                        specification,
                        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "firstName")
                                .and(Sort.by(Sort.Direction.ASC, "lastName"))
                                .and(Sort.by(Sort.Direction.ASC, "phone"))
                                .and(Sort.by(Sort.Direction.ASC, "email"))
                                .and(Sort.by(Sort.Direction.DESC, "createdAt")))
                )
                .map(ContactResponse::from);

        log.info(
                "Contacts listed searchPresent={} returned={} total={} page={} totalPages={}",
                term != null,
                response.getNumberOfElements(),
                response.getTotalElements(),
                response.getNumber(),
                response.getTotalPages()
        );
        return PageResponse.from(response);
    }

    public boolean belongsToTenant(UUID tenantId, UUID contactId) {
        boolean belongs = repository.existsByIdAndTenantId(contactId, tenantId);
        log.debug("Contact tenant ownership checked contactId={} belongs={}", contactId, belongs);
        return belongs;
    }

    private ContactEntity requireContact(UUID tenantId, UUID contactId) {
        return repository.findByIdAndTenantId(contactId, tenantId)
                .orElseThrow(() -> {
                    log.warn("Contact lookup failed contactId={}", contactId);
                    return new ApiException(
                            HttpStatus.NOT_FOUND,
                            "Contact not found",
                            "No contact was found for this tenant."
                    );
                });
    }

    private Values validateAndNormalize(ContactRequest request, boolean allowNameOnly) {
        String phone = blankToNull(request.phone());
        String email = blankToNull(request.email());
        String normalizedPhone = normalizePhone(phone);
        String normalizedEmail = email == null ? null : email.toLowerCase(Locale.ROOT);

        if (normalizedPhone == null && normalizedEmail == null && !(allowNameOnly && hasAnyName(request))) {
            log.warn("Contact validation failed because neither phone nor email was supplied");
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Contact needs an identifier",
                    "Enter at least a phone number or email address. A contact name is optional."
            );
        }

        String firstName = blankToNull(request.firstName());
        String lastName = blankToNull(request.lastName());
        String displayName = blankToNull(request.displayName());
        if (displayName == null) {
            String combinedName = ((firstName == null ? "" : firstName) + " "
                    + (lastName == null ? "" : lastName)).trim();
            displayName = combinedName.isBlank() ? null : combinedName;
        }

        return new Values(
                firstName,
                lastName,
                displayName,
                phone,
                normalizedPhone,
                email,
                normalizedEmail,
                blankToNull(request.notes())
        );
    }

    private void rejectDuplicates(UUID tenantId, Values values, ContactEntity current) {
        if (values.normalizedPhone() != null
                && repository.existsByTenantIdAndNormalizedPhone(tenantId, values.normalizedPhone())
                && (current == null || !values.normalizedPhone().equals(current.getNormalizedPhone()))) {
            log.warn("Contact duplicate rejected field=phone currentContactId={}", current == null ? null : current.getId());
            throw new ApiException(HttpStatus.CONFLICT, "Phone number already exists", "A contact already uses this phone number.");
        }
        if (values.normalizedEmail() != null
                && repository.existsByTenantIdAndNormalizedEmail(tenantId, values.normalizedEmail())
                && (current == null || !values.normalizedEmail().equals(current.getNormalizedEmail()))) {
            log.warn("Contact duplicate rejected field=email currentContactId={}", current == null ? null : current.getId());
            throw new ApiException(HttpStatus.CONFLICT, "Email address already exists", "A contact already uses this email address.");
        }
    }

    private boolean hasAnyName(ContactRequest request) {
        return hasText(request.firstName()) || hasText(request.lastName()) || hasText(request.displayName());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizePhone(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Values(
            String firstName,
            String lastName,
            String displayName,
            String phone,
            String normalizedPhone,
            String email,
            String normalizedEmail,
            String notes
    ) {
    }
}

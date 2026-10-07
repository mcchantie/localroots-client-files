-- Formal quote requests may identify a customer by name without phone/email.
-- Retain the requirement that a contact has at least one usable identifying detail.
ALTER TABLE contacts DROP CONSTRAINT IF EXISTS ck_contacts_usable_identifier;
ALTER TABLE contacts ADD CONSTRAINT ck_contacts_usable_identifier CHECK (
    normalized_phone IS NOT NULL OR normalized_email IS NOT NULL
    OR NULLIF(TRIM(first_name), '') IS NOT NULL
    OR NULLIF(TRIM(last_name), '') IS NOT NULL
    OR NULLIF(TRIM(display_name), '') IS NOT NULL
) NOT VALID;

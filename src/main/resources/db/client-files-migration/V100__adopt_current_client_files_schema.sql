-- Automatic Flyway adoption for existing shared CRM/Client Files databases.
-- Uses client_files_flyway_schema_history, never the CRM history table.
-- Nonempty schemas without this history are baselined at 99, then reconciled here.
-- Existing files/contact IDs are retained. Legacy V1-V5 stay outside this location
-- because they may already have been applied manually and V4 was not idempotent.
-- Do not edit this migration after application; add V101 and later changes here.

-- Reconciliation derived from legacy V1__integrate_client_files_with_localroots.sql
-- Client Files integrates with the shared Local Roots platform tables.
-- This migration is safe to run against either:
--   1. the existing Local Roots PostgreSQL schema, or
--   2. an empty PostgreSQL database used for a standalone first deployment.

-- No extension is needed: application code generates UUIDs.

CREATE TABLE IF NOT EXISTS tenants (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    notification_email VARCHAR(255),
    notification_phone VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS contacts (
    id UUID PRIMARY KEY,
    address_line1 VARCHAR(255),
    address_line2 VARCHAR(255),
    city VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    email VARCHAR(320),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    lifecycle_stage VARCHAR(255) NOT NULL DEFAULT 'LEAD',
    message TEXT,
    phone VARCHAR(255),
    postal_code VARCHAR(255),
    state VARCHAR(255),
    status VARCHAR(255) NOT NULL DEFAULT 'NEW',
    updated_at TIMESTAMPTZ NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    follow_up_due_at TIMESTAMPTZ,
    internal_notes TEXT,
    owner VARCHAR(255),
    follow_up_action VARCHAR(255) NOT NULL DEFAULT 'NONE',
    pipeline_status VARCHAR(255) NOT NULL DEFAULT 'NEW_LEAD',
    display_name VARCHAR(255),
    normalized_phone VARCHAR(30),
    normalized_email VARCHAR(320),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT contacts_lifecycle_stage_check CHECK (lifecycle_stage IN ('LEAD', 'CLIENT')),
    CONSTRAINT contacts_status_check CHECK (status IN ('NEW', 'CONTACTED', 'BOOKED', 'NOT_INTERESTED')),
    CONSTRAINT contacts_follow_up_action_check CHECK (follow_up_action IN (
        'NONE', 'REVIEW_PHOTOS_OR_VIDEOS', 'SEND_QUOTE', 'GET_SCHEDULED',
        'REPLY_TO_CUSTOMER', 'COLLECT_MORE_INFO', 'CHECK_PAYMENT',
        'FOLLOW_UP_AFTER_QUOTE', 'SEND_FORMAL_QUOTE'
    )),
    CONSTRAINT contacts_pipeline_status_check CHECK (pipeline_status IN (
        'NEW_LEAD', 'NEEDS_REVIEW', 'NEEDS_QUOTE', 'QUOTE_SENT',
        'NEEDS_SCHEDULING', 'SCHEDULED', 'WON', 'LOST', 'FOLLOW_UP', 'ARCHIVED'
    ))
);

CREATE TABLE IF NOT EXISTS contact_attachments (
    id UUID PRIMARY KEY,
    content_type VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    external_attachment_id VARCHAR(255),
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT,
    s3_bucket VARCHAR(255) NOT NULL,
    s3_key VARCHAR(1024) NOT NULL,
    source_system VARCHAR(255) NOT NULL,
    source_url TEXT,
    contact_id UUID REFERENCES contacts(id),
    conversation_id UUID,
    message_event_id UUID,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    estimate_id UUID,
    parent_attachment_id UUID,
    category VARCHAR(32) NOT NULL DEFAULT 'OTHER',
    file_kind VARCHAR(32) NOT NULL DEFAULT 'OTHER',
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    display_name VARCHAR(255) NOT NULL,
    actual_size_bytes BIGINT,
    checksum_sha256_base64 VARCHAR(128),
    etag VARCHAR(255),
    description VARCHAR(2000),
    metadata_json TEXT,
    failure_reason VARCHAR(1000),
    created_by_user_id UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT contact_attachments_source_system_check CHECK (source_system IN (
        'QUO', 'FACEBOOK', 'JOBBER', 'WEBSITE', 'GMAIL', 'MANUAL'
    )),
    CONSTRAINT contact_attachments_category_check CHECK (category IN (
        'ESTIMATES', 'QUOTES', 'LANDGLIDE', 'PROPERTY_PHOTOS', 'VIDEOS', 'DOCUMENTS', 'OTHER'
    )),
    CONSTRAINT contact_attachments_file_kind_check CHECK (file_kind IN (
        'IMAGE', 'VIDEO', 'DOCUMENT', 'OTHER'
    )),
    CONSTRAINT contact_attachments_status_check CHECK (status IN (
        'PENDING_UPLOAD', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED'
    )),
    CONSTRAINT fk_contact_attachments_parent
        FOREIGN KEY (parent_attachment_id) REFERENCES contact_attachments(id)
);

-- Existing Local Roots contacts currently require both names. Client Files must allow
-- phone-only and email-only contacts, so names become nullable.
ALTER TABLE contacts ALTER COLUMN first_name DROP NOT NULL;
ALTER TABLE contacts ALTER COLUMN last_name DROP NOT NULL;
ALTER TABLE contacts ALTER COLUMN email TYPE VARCHAR(320);

-- Defaults let Client Files insert a shared CRM contact without mapping every workflow field.
ALTER TABLE contacts ALTER COLUMN lifecycle_stage SET DEFAULT 'LEAD';
ALTER TABLE contacts ALTER COLUMN status SET DEFAULT 'NEW';
ALTER TABLE contacts ALTER COLUMN follow_up_action SET DEFAULT 'NONE';
ALTER TABLE contacts ALTER COLUMN pipeline_status SET DEFAULT 'NEW_LEAD';

ALTER TABLE contacts ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);
ALTER TABLE contacts ADD COLUMN IF NOT EXISTS normalized_phone VARCHAR(30);
ALTER TABLE contacts ADD COLUMN IF NOT EXISTS normalized_email VARCHAR(320);
ALTER TABLE contacts ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

UPDATE contacts
SET normalized_phone = NULLIF(regexp_replace(phone, '[^0-9]', '', 'g'), ''),
    normalized_email = NULLIF(lower(btrim(email)), ''),
    display_name = COALESCE(
        NULLIF(btrim(display_name), ''),
        NULLIF(btrim(concat_ws(' ', NULLIF(btrim(first_name), ''), NULLIF(btrim(last_name), ''))), '')
    )
WHERE normalized_phone IS DISTINCT FROM NULLIF(regexp_replace(phone, '[^0-9]', '', 'g'), '')
   OR normalized_email IS DISTINCT FROM NULLIF(lower(btrim(email)), '')
   OR display_name IS DISTINCT FROM COALESCE(
        NULLIF(btrim(display_name), ''),
        NULLIF(btrim(concat_ws(' ', NULLIF(btrim(first_name), ''), NULLIF(btrim(last_name), ''))), '')
   );

-- Keep normalized identifiers correct for contacts created by any Local Roots service,
-- not only this Client Files backend.
CREATE OR REPLACE FUNCTION localroots_normalize_contact_identifiers()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.phone := NULLIF(btrim(NEW.phone), '');
    NEW.email := NULLIF(btrim(NEW.email), '');
    NEW.normalized_phone := NULLIF(regexp_replace(NEW.phone, '[^0-9]', '', 'g'), '');
    NEW.normalized_email := NULLIF(lower(NEW.email), '');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_contacts_normalize_identifiers ON contacts;
CREATE TRIGGER trg_contacts_normalize_identifiers
BEFORE INSERT OR UPDATE OF phone, email
ON contacts
FOR EACH ROW
EXECUTE FUNCTION localroots_normalize_contact_identifiers();

-- NOT VALID preserves any historical name-only rows while enforcing the rule for
-- all new or subsequently updated contacts.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'contacts'::regclass
          AND conname = 'ck_contacts_usable_identifier'
    ) THEN
        ALTER TABLE contacts
            ADD CONSTRAINT ck_contacts_usable_identifier
            CHECK (normalized_phone IS NOT NULL OR normalized_email IS NOT NULL)
            NOT VALID;
    END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS ix_contacts_tenant_created
    ON contacts (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_contacts_tenant_display_name
    ON contacts (tenant_id, lower(display_name));
CREATE INDEX IF NOT EXISTS ix_contacts_tenant_normalized_phone
    ON contacts (tenant_id, normalized_phone)
    WHERE normalized_phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_contacts_tenant_normalized_email
    ON contacts (tenant_id, normalized_email)
    WHERE normalized_email IS NOT NULL;

-- Expand the existing attachment table instead of creating a second attachment model.
ALTER TABLE contact_attachments ALTER COLUMN contact_id DROP NOT NULL;
ALTER TABLE contact_attachments ALTER COLUMN s3_key TYPE VARCHAR(1024);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS estimate_id UUID;
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS parent_attachment_id UUID;
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS category VARCHAR(32);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS file_kind VARCHAR(32);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS status VARCHAR(32);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS actual_size_bytes BIGINT;
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS checksum_sha256_base64 VARCHAR(128);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS etag VARCHAR(255);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS description VARCHAR(2000);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS metadata_json TEXT;
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(1000);
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS created_by_user_id UUID;
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS uploaded_at TIMESTAMPTZ;
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE contact_attachments ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

UPDATE contact_attachments
SET content_type = NULLIF(btrim(content_type), ''),
    category = COALESCE(
        category,
        CASE
            WHEN lower(content_type) LIKE 'image/%' THEN 'PROPERTY_PHOTOS'
            WHEN lower(content_type) LIKE 'video/%' THEN 'VIDEOS'
            WHEN lower(content_type) = 'application/pdf' THEN 'DOCUMENTS'
            ELSE 'OTHER'
        END
    ),
    file_kind = COALESCE(
        file_kind,
        CASE
            WHEN lower(content_type) LIKE 'image/%' THEN 'IMAGE'
            WHEN lower(content_type) LIKE 'video/%' THEN 'VIDEO'
            WHEN lower(content_type) LIKE 'application/%' OR lower(content_type) LIKE 'text/%' THEN 'DOCUMENT'
            ELSE 'OTHER'
        END
    ),
    status = COALESCE(status, 'READY'),
    display_name = COALESCE(NULLIF(btrim(display_name), ''), file_name),
    updated_at = COALESCE(updated_at, created_at),
    actual_size_bytes = COALESCE(actual_size_bytes, file_size),
    uploaded_at = COALESCE(uploaded_at, created_at)
WHERE content_type IS DISTINCT FROM NULLIF(btrim(content_type), '')
   OR category IS NULL
   OR file_kind IS NULL
   OR status IS NULL
   OR display_name IS NULL
   OR btrim(display_name) = ''
   OR updated_at IS NULL
   OR (actual_size_bytes IS NULL AND file_size IS NOT NULL)
   OR uploaded_at IS NULL;

CREATE OR REPLACE FUNCTION localroots_prepare_contact_attachment()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.category := COALESCE(
        NEW.category,
        CASE
            WHEN lower(NEW.content_type) LIKE 'image/%' THEN 'PROPERTY_PHOTOS'
            WHEN lower(NEW.content_type) LIKE 'video/%' THEN 'VIDEOS'
            WHEN lower(NEW.content_type) = 'application/pdf' THEN 'DOCUMENTS'
            ELSE 'OTHER'
        END
    );
    NEW.file_kind := COALESCE(
        NEW.file_kind,
        CASE
            WHEN lower(NEW.content_type) LIKE 'image/%' THEN 'IMAGE'
            WHEN lower(NEW.content_type) LIKE 'video/%' THEN 'VIDEO'
            WHEN lower(NEW.content_type) LIKE 'application/%' OR lower(NEW.content_type) LIKE 'text/%' THEN 'DOCUMENT'
            ELSE 'OTHER'
        END
    );
    NEW.status := COALESCE(NEW.status, 'READY');
    NEW.display_name := COALESCE(NULLIF(btrim(NEW.display_name), ''), NEW.file_name);
    NEW.updated_at := COALESCE(NEW.updated_at, NEW.created_at, now());
    IF NEW.status IN ('READY', 'UPLOADED') AND NEW.uploaded_at IS NULL THEN
        NEW.uploaded_at := COALESCE(NEW.created_at, now());
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_contact_attachments_prepare ON contact_attachments;
CREATE TRIGGER trg_contact_attachments_prepare
BEFORE INSERT OR UPDATE OF content_type, file_name, category, file_kind, status, display_name, updated_at, uploaded_at
ON contact_attachments
FOR EACH ROW
EXECUTE FUNCTION localroots_prepare_contact_attachment();
ALTER TABLE contact_attachments ALTER COLUMN category SET DEFAULT 'OTHER';
ALTER TABLE contact_attachments ALTER COLUMN category SET NOT NULL;
ALTER TABLE contact_attachments ALTER COLUMN file_kind SET DEFAULT 'OTHER';
ALTER TABLE contact_attachments ALTER COLUMN file_kind SET NOT NULL;
ALTER TABLE contact_attachments ALTER COLUMN status SET DEFAULT 'READY';
ALTER TABLE contact_attachments ALTER COLUMN status SET NOT NULL;
ALTER TABLE contact_attachments ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE contact_attachments ALTER COLUMN updated_at SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'contact_attachments'::regclass
          AND conname = 'contact_attachments_category_check'
    ) THEN
        ALTER TABLE contact_attachments
            ADD CONSTRAINT contact_attachments_category_check
            CHECK (category IN ('ESTIMATES', 'QUOTES', 'LANDGLIDE', 'PROPERTY_PHOTOS', 'VIDEOS', 'DOCUMENTS', 'OTHER'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'contact_attachments'::regclass
          AND conname = 'contact_attachments_file_kind_check'
    ) THEN
        ALTER TABLE contact_attachments
            ADD CONSTRAINT contact_attachments_file_kind_check
            CHECK (file_kind IN ('IMAGE', 'VIDEO', 'DOCUMENT', 'OTHER'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'contact_attachments'::regclass
          AND conname = 'contact_attachments_status_check'
    ) THEN
        ALTER TABLE contact_attachments
            ADD CONSTRAINT contact_attachments_status_check
            CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'contact_attachments'::regclass
          AND conname = 'fk_contact_attachments_parent'
    ) THEN
        ALTER TABLE contact_attachments
            ADD CONSTRAINT fk_contact_attachments_parent
            FOREIGN KEY (parent_attachment_id) REFERENCES contact_attachments(id);
    END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS ix_contact_attachments_tenant_created
    ON contact_attachments (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_contact_attachments_tenant_contact_created
    ON contact_attachments (tenant_id, contact_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_contact_attachments_tenant_category
    ON contact_attachments (tenant_id, category);
CREATE INDEX IF NOT EXISTS ix_contact_attachments_tenant_status
    ON contact_attachments (tenant_id, status);
CREATE INDEX IF NOT EXISTS ix_contact_attachments_active
    ON contact_attachments (tenant_id, deleted_at)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_contact_attachments_bucket_key
    ON contact_attachments (s3_bucket, s3_key);


-- Reconciliation derived from legacy V2__support_estimator_source_and_file_browsing.sql
-- Adds the Estimator as a trusted attachment source and indexes used by file search/sorting.

ALTER TABLE contact_attachments
    DROP CONSTRAINT IF EXISTS contact_attachments_source_system_check;

ALTER TABLE contact_attachments
    ADD CONSTRAINT contact_attachments_source_system_check
    CHECK (source_system IN (
        'QUO', 'FACEBOOK', 'JOBBER', 'WEBSITE', 'GMAIL', 'MANUAL', 'ESTIMATOR'
    ));

CREATE INDEX IF NOT EXISTS ix_contact_attachments_tenant_display_name
    ON contact_attachments (tenant_id, lower(display_name));

CREATE INDEX IF NOT EXISTS ix_contact_attachments_tenant_file_name
    ON contact_attachments (tenant_id, lower(file_name));

CREATE INDEX IF NOT EXISTS ix_contact_attachments_tenant_updated
    ON contact_attachments (tenant_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS ix_contact_attachments_tenant_size
    ON contact_attachments (tenant_id, file_size);


-- Reconciliation derived from legacy V3__add_quotes_attachment_category.sql
-- Apply before deploying the backend that accepts QUOTES and removes LANDGLIDE.
-- Reclassify any old metadata without moving or deleting stored objects.
UPDATE contact_attachments
SET category = 'PROPERTY_PHOTOS'
WHERE category = 'LANDGLIDE';

ALTER TABLE contact_attachments
    DROP CONSTRAINT IF EXISTS contact_attachments_category_check;

ALTER TABLE contact_attachments
    ADD CONSTRAINT contact_attachments_category_check
    CHECK (category IN (
        'ESTIMATES', 'QUOTES', 'PROPERTY_PHOTOS',
        'VIDEOS', 'DOCUMENTS', 'OTHER'
    ));


-- Reconciliation derived from legacy V4__estimator_quote_contact_links.sql
create table if not exists estimator_quote_contact_links (
 tenant_id uuid not null,
 attachment_id uuid not null references contact_attachments(id) on delete cascade,
 estimate_id uuid not null,
 contact_id uuid not null references contacts(id),
 primary key (tenant_id, attachment_id)
);


-- Reconciliation derived from legacy V5__allow_named_quote_contacts.sql
-- Formal quote requests may identify a customer by name without phone/email.
-- Retain the requirement that a contact has at least one usable identifying detail.
ALTER TABLE contacts DROP CONSTRAINT IF EXISTS ck_contacts_usable_identifier;
ALTER TABLE contacts ADD CONSTRAINT ck_contacts_usable_identifier CHECK (
    normalized_phone IS NOT NULL OR normalized_email IS NOT NULL
    OR NULLIF(TRIM(first_name), '') IS NOT NULL
    OR NULLIF(TRIM(last_name), '') IS NOT NULL
    OR NULLIF(TRIM(display_name), '') IS NOT NULL
) NOT VALID;


-- Preserve current CRM states/defaults when adopting legacy Client Files SQL.
ALTER TABLE contacts
    DROP CONSTRAINT IF EXISTS contacts_pipeline_status_check;

ALTER TABLE contacts
    ADD CONSTRAINT contacts_pipeline_status_check
    CHECK (
        pipeline_status IN (
            'INQUIRY',
            'ESTIMATE',
            'QUOTE',
            'SCHEDULE',
            'JOB_COMPLETE',
            'NEW_LEAD',
            'NEEDS_REVIEW',
            'NEEDS_QUOTE',
            'QUOTE_SENT',
            'NEEDS_SCHEDULING',
            'SCHEDULED',
            'WON',
            'LOST',
            'FOLLOW_UP',
            'ARCHIVED'
        )
    );

ALTER TABLE contacts
    DROP CONSTRAINT IF EXISTS contacts_follow_up_action_check;

ALTER TABLE contacts
    ADD CONSTRAINT contacts_follow_up_action_check
    CHECK (
        follow_up_action IS NULL
        OR follow_up_action IN (
            'NONE',
            'COLLECT_MORE_INFO',
            'REPLY_TO_CUSTOMER',
            'SEND_ESTIMATE',
            'FOLLOW_UP_AFTER_ESTIMATE',
            'REVISE_ESTIMATE',
            'SEND_QUOTE',
            'FOLLOW_UP_AFTER_QUOTE',
            'GET_SCHEDULED',
            'CHECK_PAYMENT',
            'REVIEW_PHOTOS_OR_VIDEOS',
            'SEND_FORMAL_QUOTE'
        )
    );

ALTER TABLE contacts ALTER COLUMN pipeline_status SET DEFAULT 'INQUIRY';

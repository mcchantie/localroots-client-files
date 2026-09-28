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

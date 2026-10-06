create table estimator_quote_contact_links (
 tenant_id uuid not null,
 attachment_id uuid not null references contact_attachments(id) on delete cascade,
 estimate_id uuid not null,
 contact_id uuid not null references contacts(id),
 primary key (tenant_id, attachment_id)
);

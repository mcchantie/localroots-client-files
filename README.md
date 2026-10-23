# LocalRoots Client Files

Client Files stores and serves customer attachments while using the CRM-owned contact records.

## Local development ports

```text
Client Files UI   5183
Client Files API  8083
CRM Postgres      5481  (shared; CRM owns this database)
```

Client Files intentionally does **not** have its own `5483` PostgreSQL instance today. Its local
profile connects to the CRM database at `localhost:5481/localroots_crm` because the current
Client Files schema shares the LocalRoots contact/database model.

The standard local defaults are:

Run the backend with the `local` Spring profile and the UI on port 5183.

## Shared Local Roots sign-in

Sign in with your existing **CRM email and password**. Client Files now reads the same `app_users` record and BCrypt password hash through its existing `CRM_DB_URL`, `CRM_DB_USER`, and `CRM_DB_PASSWORD` connection. The CRM HTTP service does not need to be running; the database must be available. No schema migration or duplicate account is needed.

`CLIENT_FILES_ADMIN_USERNAME` and `CLIENT_FILES_ADMIN_PASSWORD` are no longer used. Keep the existing tenant UUID, JWT signing secret/issuer, CORS, and storage settings. `CLIENT_FILES_TENANT_ID` must identify the business in that database. Active users assigned to that tenant and CRM administrators can sign in. A password change or account deactivation in CRM applies at the next sign-in; already issued tokens remain valid until expiry or signing-key rotation. The JSON login request still uses the `username` field for compatibility, but its value is the CRM email address. The UI labels it Email.

This is one stored identity, with separate app sessions for now. A future login portal should authenticate through a dedicated authentication service and provide SSO to all apps; the account table can move out of the CRM database at that point.

## Railway Storage Bucket

Create a Bucket in the same Railway environment as the Client Files API. In the
API service's Variables tab, set the following **reference variables** using
the bucket service name shown on your Railway canvas (replace `ClientFilesBucket`
in these examples with that exact name):

| API variable | Railway reference |
| --- | --- |
| `CLIENT_FILES_S3_BUCKET` | `${{ClientFilesBucket.BUCKET}}` |
| `CLIENT_FILES_S3_REGION` | `${{ClientFilesBucket.REGION}}` |
| `CLIENT_FILES_S3_ENDPOINT` | `${{ClientFilesBucket.ENDPOINT}}` |
| `CLIENT_FILES_S3_ACCESS_KEY_ID` | `${{ClientFilesBucket.ACCESS_KEY_ID}}` |
| `CLIENT_FILES_S3_SECRET_ACCESS_KEY` | `${{ClientFilesBucket.SECRET_ACCESS_KEY}}` |

Use the **BUCKET** value, not `RAILWAY_BUCKET_NAME`. Pass the base endpoint from
the Credentials tab; the SDK adds the bucket host. For an older bucket whose
Credentials tab specifies path-style URLs, also set
`CLIENT_FILES_S3_PATH_STYLE_ACCESS=true`.

The UI uploads directly with a presigned PUT URL. Configure the bucket's CORS
rules to allow the deployed UI origin, `PUT` and `GET`, and the `Content-Type`
and `x-amz-checksum-sha256` request headers. Keep the bucket private. Existing
attachment database rows still point to their original object keys; copy those
objects into the Railway bucket before changing production storage variables.
Local AWS S3 usage continues with `CLIENT_FILES_S3_BUCKET`, `AWS_REGION`, and
the AWS SDK default credential chain when the Railway-specific variables are
unset.

## Attachment categories and display names

`QUOTES` is accepted for document and image uploads (PDF, text, or screenshots).
`LANDGLIDE` is removed; the migration recategorizes any old metadata rows as
`PROPERTY_PHOTOS` without touching stored objects. The PostgreSQL category check must accept
`QUOTES` before the new backend is deployed. Because Flyway is disabled, apply
`src/main/resources/db/migration/V3__add_quotes_attachment_category.sql`
manually to each database after reviewing it.

The upload initializer already accepts an optional `displayName` in the request
and returns it in attachment responses. To edit a name later, send
`PATCH /api/v1/attachments/{attachmentId}/display-name` with
`{"displayName":"Quote for Smith"}`. Names must be nonblank, at most 255
characters, and on one line. Renaming changes the display name and generated
download filename, not the original filename or S3 key. The frontend can
suggest friendly names before upload, then send the chosen name to the API.


## CRM file references

Client Files owns the existing `contact_attachments` table in the shared CRM database.
Do not delete it. CRM V104 adds shared contact links and copies attachment associations
into lightweight CRM file references; no file bytes or IDs move.

Configure `CLIENT_FILES_CRM_API_KEY` (a unique secret of at least 32 characters, shared
only with the CRM backend) and `CLIENT_FILES_CRM_TENANT_ID` (defaults to
`CLIENT_FILES_TENANT_ID`). The authenticated internal read
`GET /api/internal/crm/contacts/{contactId}/files/{fileId}` returns 204 only when a
READY, undeleted file belongs to that contact and configured tenant. It returns no
storage URLs or file contents. Deploy this endpoint before the corresponding CRM
change. Existing Client Files user-facing APIs and file storage are unchanged.

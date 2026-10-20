# Base44 Integration Contract

## API base URL

Use the Railway HTTPS domain, without a trailing slash:

```text
https://your-client-files-api.up.railway.app
```

All JSON requests use:

```http
Content-Type: application/json
```

Protected requests use:

```http
Authorization: Bearer <accessToken>
```

Do not send `X-Tenant-Id` in production. The API derives the tenant from the signed token.

## Authentication

### Login

```http
POST /api/v1/auth/login
```

Request:

```json
{
  "username": "crystal",
  "password": "the Railway-configured password"
}
```

Response:

```json
{
  "tokenType": "Bearer",
  "accessToken": "eyJ...",
  "expiresAt": "2026-07-12T23:00:00Z",
  "username": "crystal",
  "tenantId": "11111111-1111-1111-1111-111111111111"
}
```

Store the token for the current browser session. When any protected request returns `401`, clear the token and return to the login screen.

### Current user

```http
GET /api/v1/auth/me
```

## Contacts

A contact name is optional. At least one usable phone number or email address is required.

### Create

```http
POST /api/v1/contacts
```

```json
{
  "firstName": null,
  "lastName": null,
  "displayName": null,
  "phone": "832-555-0100",
  "email": null,
  "notes": "Lead sent a lawn photo but did not provide a name."
}
```

### List and search

```http
GET /api/v1/contacts?search=&page=0&size=50
```

### Read

```http
GET /api/v1/contacts/{contactId}
```

### Update

```http
PUT /api/v1/contacts/{contactId}
```

### Service properties and lawn measurements

```http
GET /api/v1/contacts/{contactId}/properties
```

Returns the CRM-owned service properties for this contact, including `totalLawnAreaSqFt`,
`sectionsComplete`, measurement source/status/date, and named lawn `sections` with
`areaSqFt` and optional `grassType`. This endpoint uses the Client Files bearer token
and enforces the same tenant boundary as contact reads. Editing is done in CRM.
The CRM `V101__add_service_properties_and_lawn_sections.sql` migration must run
before this endpoint is deployed.

## Attachments

Categories:

```text
ESTIMATES
LANDGLIDE
PROPERTY_PHOTOS
VIDEOS
DOCUMENTS
OTHER
```

Statuses:

```text
PENDING_UPLOAD
UPLOADED
PROCESSING
READY
FAILED
```

### List

```http
GET /api/v1/attachments?page=0&size=25
GET /api/v1/attachments?contactId={uuid}
GET /api/v1/attachments?unassigned=true
GET /api/v1/attachments?category=PROPERTY_PHOTOS
GET /api/v1/attachments?status=READY
GET /api/v1/attachments?includeDeleted=true
GET /api/v1/attachments?search=lawn&sortBy=NAME&sortDirection=ASC
GET /api/v1/attachments?fileKind=IMAGE&sortBy=CREATED_AT&sortDirection=DESC
```

Filters may be combined. When `contactId` is present it takes precedence over `unassigned=true`.

Additional file-browser parameters:

- `search`: display name, original filename, content type, source system, or description
- `fileKind`: `IMAGE`, `VIDEO`, `DOCUMENT`, or `OTHER`
- `sortBy`: `NAME`, `CREATED_AT`, `UPDATED_AT`, or `SIZE`
- `sortDirection`: `ASC` or `DESC`

### Initialize an upload

```http
POST /api/v1/attachments/uploads
```

```json
{
  "contactId": "optional-contact-uuid",
  "estimateId": null,
  "parentAttachmentId": null,
  "category": "PROPERTY_PHOTOS",
  "originalFileName": "back-yard.jpg",
  "displayName": "Back yard before grading",
  "contentType": "image/jpeg",
  "sizeBytes": 2458134,
  "checksumSha256Base64": null,
  "sourceSystem": "base44",
  "description": null,
  "metadata": {
    "capturedFrom": "dashboard"
  }
}
```

The response contains:

- `attachmentId`
- `method`, currently `PUT`
- `uploadUrl`
- `expiresAt`
- `requiredHeaders`

### Upload directly to S3

Use the exact HTTP method, presigned URL, and every returned required header. The request body is the raw browser `File` object. Do not send the Railway bearer token to S3.

```javascript
await fetch(upload.uploadUrl, {
  method: upload.method,
  headers: upload.requiredHeaders,
  body: file
});
```

### Complete the upload

After S3 returns a 2xx response:

```http
POST /api/v1/attachments/{attachmentId}/complete
Authorization: Bearer <accessToken>
```

Only show the file as successfully stored after this endpoint returns `READY`.

### Open or download

```http
POST /api/v1/attachments/{attachmentId}/download-url?download=false
POST /api/v1/attachments/{attachmentId}/download-url?download=true
```

Open the returned short-lived URL immediately.

### Assign, reassign, or unassign

```http
POST /api/v1/attachments/{attachmentId}/assign
```

Assign or reassign:

```json
{
  "contactId": "contact-uuid"
}
```

Return to Unassigned:

```json
{
  "contactId": null
}
```


### Batch assign and categorize existing attachments

```http
POST /api/v1/attachments/batch-update
```

```json
{
  "attachmentIds": ["attachment-uuid-1", "attachment-uuid-2"],
  "updateContact": true,
  "contactId": "contact-uuid-or-null",
  "category": "PROPERTY_PHOTOS"
}
```

Set `updateContact` to `true` with `contactId: null` to move the selected attachments to Unassigned. Set `updateContact` to `false` when changing only the category.

### Soft delete and restore

```http
DELETE /api/v1/attachments/{attachmentId}
POST /api/v1/attachments/{attachmentId}/restore
```

Deleting an active file moves it to Trash and returns the attachment with `deletedAt` set.
List Trash with `GET /api/v1/attachments?deletedOnly=true`.

### Permanently delete from Trash

```http
DELETE /api/v1/attachments/{attachmentId}/permanent
Authorization: Bearer <token>
```

This returns `204 No Content` after deleting the stored S3 object and attachment record.
There is no request body. Only a file already in Trash can be permanently deleted.
An active file, or a file with linked child attachments, returns `409 Conflict`.
Delete linked files first. A missing file or a file belonging to another tenant returns `404`.
If S3 deletion fails, the attachment remains in Trash and the API returns an error;
retry the same request. Do not call the regular DELETE endpoint again for this action.

## Error handling

The API uses `application/problem+json`. Display `title`, `detail`, and field-level `errors` when present. Also log or display the `X-Correlation-Id` response header for troubleshooting.

Common statuses:

- `400`: invalid request
- `401`: missing, invalid, or expired login token
- `404`: resource is not available to this tenant
- `409`: duplicate contact or invalid attachment state
- `413`: file too large
- `415`: unsupported content type
- `422`: S3 upload verification failed
- `502`: S3 request failed

## Recommended Base44 client wrapper

Use one API helper so every protected call handles authorization and expiration consistently:

```javascript
const API_BASE_URL = "https://your-client-files-api.up.railway.app";

async function apiFetch(path, options = {}) {
  const token = sessionStorage.getItem("clientFilesAccessToken");
  const headers = new Headers(options.headers || {});
  headers.set("Accept", "application/json");

  if (options.body && !(options.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers
  });

  if (response.status === 401) {
    sessionStorage.removeItem("clientFilesAccessToken");
    window.location.assign("/login");
    throw new Error("Your session expired. Please sign in again.");
  }

  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || problem.title || `Request failed: ${response.status}`);
  }

  if (response.status === 204) {
    return null;
  }
  return response.json();
}
```

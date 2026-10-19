# Client Files API Production Deployment

This release deploys the Spring Boot API to Railway. Base44 hosts the dashboard. Railway PostgreSQL stores contacts and attachment metadata, while the production S3 bucket stores the actual files.

## 1. Run the release checks locally

```bash
./mvnw clean test
```

Run the API locally with the development database and S3 bucket:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

The local login defaults are:

```text
username: admin
password: local-development-only
```

Request a token:

```bash
curl -s http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"local-development-only"}'
```

## 2. Generate stable production values

Use the existing Texas Top Dressing tenant UUID from the CRM database for `CLIENT_FILES_TENANT_ID`. Do not generate a replacement tenant for an existing business.

Accounts and BCrypt password hashes live only in the CRM `app_users` table. Sign in with a CRM email and password; no separate Client Files administrator password is configured. Use the existing shared database connection, not an empty new database.

Generate the JWT signing secret:

```bash
openssl rand -base64 32
```

Set the result as `CLIENT_FILES_JWT_SECRET_BASE64`.

## 3. Commit and push

```bash
git add .
git commit -m "prepare client files api for base44 and railway"
git push
```

Railway uses the root `Dockerfile` and `railway.toml`.

## 4. Add Railway PostgreSQL

1. Create or open the Railway project.
2. Use the existing CRM PostgreSQL service containing `app_users`, tenants, contacts, and attachments.
3. Open the Client Files application service.
4. Add PostgreSQL reference variables using the actual database service name:

```text
PGHOST=${{Postgres.PGHOST}}
PGPORT=${{Postgres.PGPORT}}
PGDATABASE=${{Postgres.PGDATABASE}}
PGUSER=${{Postgres.PGUSER}}
PGPASSWORD=${{Postgres.PGPASSWORD}}
```

## 5. Add Railway application variables

Use `deploy/railway-environment.example` as the checklist. Required values include:

```text
SPRING_PROFILES_ACTIVE=production
CLIENT_FILES_TENANT_ID=<stable UUID>
CLIENT_FILES_JWT_SECRET_BASE64=<openssl rand -base64 32 output>
CLIENT_FILES_JWT_ISSUER=client-files-api
CLIENT_FILES_ACCESS_TOKEN_TTL=8h
CLIENT_FILES_ALLOWED_ORIGINS=<exact Base44 HTTPS origin>
CLIENT_FILES_S3_BUCKET=<production bucket>
AWS_REGION=us-east-2
AWS_ACCESS_KEY_ID=<production app key>
AWS_SECRET_ACCESS_KEY=<production app secret>
```

Do not include a trailing slash in the Base44 origin. Multiple exact origins may be supplied as a comma-separated value. `CLIENT_FILES_ALLOWED_ORIGIN_PATTERNS=https://*.base44.app` may be used temporarily for preview builds, but the published production origin should be listed exactly.

Production startup fails when identity, JWT, tenant, or CORS settings are unsafe or missing.

## 6. Deploy the API

Deploy the Railway service and generate its public HTTPS domain. Railway checks:

```text
/actuator/health
```

On first production startup, Flyway creates:

- `client_contacts`
- `client_attachments`
- constraints and indexes
- Flyway schema history

Hibernate validates the schema and does not modify it.

## 7. Configure the production S3 bucket

Open `deploy/s3-cors-production.json` and replace the placeholder with the exact Base44 published origin. Apply it in the S3 console or with:

```bash
aws s3api put-bucket-cors \
  --bucket <production-bucket-name> \
  --cors-configuration file://deploy/s3-cors-production.json
```

Apply `deploy/iam-production-policy.json` to the production application IAM identity after replacing the bucket placeholder. The application needs object read and write access under `tenants/*`. It does not physically delete S3 objects in this release.

## 8. Configure Base44

Set the Railway public domain as the Base44 API base URL. Build the dashboard using:

- `BASE44-INTEGRATION.md`
- `BASE44-DASHBOARD-PROMPT.md`

The dashboard sends the returned access token with protected requests:

```http
Authorization: Bearer <access-token>
```

The dashboard must never send or store AWS credentials and must not invent an `X-Tenant-Id` value.

## 9. Production smoke test

Use non-sensitive sample data first:

1. Confirm `/actuator/health` returns healthy.
2. Sign in from Base44.
3. Confirm `/api/v1/auth/me` succeeds.
4. Create a contact using only a phone number.
5. Upload a small JPEG to the contact.
6. Confirm the attachment becomes `READY`.
7. Open and download the file through a generated URL.
8. Upload a PDF without a contact.
9. Assign the PDF to the contact.
10. Soft-delete and restore the PDF.
11. Confirm S3 objects remain private.
12. Confirm the metadata exists in Railway PostgreSQL.

## 10. Initial production boundaries

Included:

- Single admin username and password stored as Railway secrets
- Signed, expiring JWT access tokens
- One configured tenant
- Contacts whose names are optional
- Direct private S3 uploads
- Contact assignment and reassignment
- Soft deletion and restore

Deferred:

- Multiple users and roles
- Refresh tokens, password reset, and MFA
- Login throttling shared across multiple application instances
- Quo synchronization
- LandGlide cropping
- HEIC conversion and thumbnails
- Multipart video uploads
- Malware and file-signature scanning
- Physical S3 deletion after retention

## 11. Rollback

Railway deployment history can restore the previous application image. Do not drop the production tables after files have been uploaded because the rows are required to locate and authorize access to the private S3 objects.


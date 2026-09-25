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

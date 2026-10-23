# Client Files automatic migrations

Spring Boot runs Flyway before JPA using `public.client_files_flyway_schema_history`.
CRM keeps its own `public.flyway_schema_history`; the services do not share version history.
The new location is `classpath:db/client-files-migration`. Existing nonempty schemas
without the Client Files history are baselined at V99 and then run the idempotent V100
reconciliation. Empty databases also run V100. Legacy `db/migration/V1` through V5
remain immutable reference scripts and are not automatically scanned.

V100 reconciles the attachment schema/indexes/triggers and existing quote links without
deleting files or changing UUIDs. It preserves the current CRM pipeline/follow-up
values and restores the INQUIRY default. Schema changes need normal database DDL
permissions; no new PostgreSQL extension is required. Hibernate validates the schema.

Keep Flyway enabled. `CLIENT_FILES_FLYWAY_ENABLED=false` is only an emergency override,
not the normal startup path. Never point Client Files at CRM's history table or baseline
at 100 to skip reconciliation. Future changes go in V101, V102, ... in this directory.

Back up the shared database before the first rollout. Deploy/restart one backend at a
time during first adoption. Verify V100 succeeded in the Client Files history and that
CRM history is unchanged. Existing H2 unit-test configuration intentionally disables
production PostgreSQL DDL; the startup test uses a minimal H2 migration fixture. CI also runs the actual V100 SQL
against PostgreSQL 16 for a fresh schema and a manually upgraded schema, checking
file preservation, CRM history isolation, current CRM defaults, and repeated startup.

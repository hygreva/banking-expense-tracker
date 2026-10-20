# Verification results

Generated 19 October 2026.

| Check | Result |
|---|---|
| Java 17 Spring Boot build | Passed |
| Backend integration tests | 12 sequential tests passed using PostgreSQL WASM (PGlite) over its PostgreSQL wire protocol |
| Database migration SQL | Executed against PGlite, including PL pgSQL functions and deferred journal triggers |
| Real HTTP API smoke test | Passed session login, fresh CSRF tokens, transfer submission, idempotent retry, balances and cursor history against the packaged JAR with PGlite |
| Angular production build | Passed with strict TypeScript and Angular template checks |
| Frontend money parsing tests | 2 passed |
| CloudFormation templates | Both passed cfn-lint |
| Real Docker Compose execution | Not run; Docker daemon is unavailable in the generation environment |
| PostgreSQL concurrency tests | Included for Docker-backed PostgreSQL, not run in the generation environment |
| Flyway lifecycle on standard PostgreSQL | Included in CI, not run locally; PGlite validation loaded the migration directly because its single-connection server cannot model Flyway's connection behavior |
| AWS deployment | Not run |

The sequential tests exercise posting, idempotency, insufficient funds, transaction rollback, review approval, review funding rechecks, persisted velocity limits, ledger database guards, report exclusion and deletion, threshold deduplication, authentication, CSRF, customer ownership, reviewer role restrictions and fractional money rejection. Several checks are grouped in one test method.

PGlite validation used one JDBC connection and excluded the concurrency tag. It validates application behavior and PostgreSQL SQL compatibility but does not establish concurrent row-lock behavior, standard server integration, production performance or AWS deployability. Run `mvn verify` with Docker to exercise the supplied Testcontainers suite on PostgreSQL 16.

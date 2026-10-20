# Implementation details

## Money movement

USD is the only currency. All monetary API values and database values are integer minor units; USD 125.50 is `12550`. Fractional JSON numbers are rejected. Angular parses decimal input as text rather than rounding floating point numbers. Positive ledger lines increase an account balance; negative lines decrease it. Each journal nets to zero. Customer accounts cannot overdraw. The special system funding account may be negative.

`TransferService.submit` is a single Spring transaction. It locks `fraud_subject` for the initiator, looks up the idempotency key, locks both account rows ordered by UUID, checks ownership, account state, funds and rules, then writes the transfer, both ledger lines, balances, audit event, outbox event and idempotency response before commit. PostgreSQL deferred constraint triggers check complete journals at commit. Triggers prohibit journal and ledger mutation, and additions to already committed journals. Reconciliation compares every account projection with its complete ledger.

The per customer lock serializes velocity evaluation and idempotency processing across backend replicas. Keys are UUIDs scoped to the authenticated customer and retained indefinitely in this demo. They are never reused for another request payload. A repeated identical request returns the original HTTP status and response. Read current state with GET after review; the original submission response is intentionally unchanged. A network failure requires retrying the same payload and key. A deadlock or lock failure produces retry guidance; automatic blind POST retries are not used.

This design favors explicit correctness over maximum throughput for one heavily used customer. Ordered account locks protect transfers initiated by different users. The API returns no recipient balance or internal owner identifier to a customer.

## Fraud and review

The immutable rule set version is stored with every transfer. Version 1 uses:

| Rule | Outcome |
|---|---|
| Sixth persisted submission in ten minutes | Reject |
| Insufficient funds | Reject |
| Amount above USD 5,000 | Review |
| Rolling 24 hour posted plus pending total above USD 10,000 including request | Review |
| Beneficiary younger than 24 hours and amount above USD 1,000 | Review |

Reject takes precedence over review. Invalid requests rejected before creating a transfer do not enter the velocity count; login and edge limits provide separate abuse controls. These thresholds are demonstration heuristics, not predictive fraud scoring.

Held transfers do not reserve funds. `decide` locks the case and transfer, then the initiator subject and both accounts. It prevents self review, uses optimistic case version checks and rechecks current account state and balance. Reviewer approval explicitly overrides the flagged risk rules; it does not recursively place the same transfer in review. Approval can still yield a rejected transfer if funds or eligibility changed. Only one terminal decision can post a journal. Review cases are visible to all authorized reviewers in this small demo; assignment queues are not implemented.

## Expenses and budgets

Transfers are internal movements and are always excluded from cash flow and spending charts. Manual activity records are the report source. They do not debit the ledger. A category foreign key includes owner and kind, preventing one customer from selecting another customer's category. Updates use a version; deletion is soft. Reports use half open date intervals, interpreted in the customer's stored time zone. A monthly budget includes expenses in that local calendar month.

Budget alerts are inserted transactionally with a unique budget and threshold key. An edit below and back above a threshold does not produce a second alert for that month's budget. The in-app outbox worker inserts notifications and marks events delivered in one transaction. Multiple replicas claim pending events with `FOR UPDATE SKIP LOCKED`. There are no remote notification calls, so local transactional delivery does not require the remote-delivery leases from the broader design document.

## Security

Spring Security performs form authentication and persists the security context in shared JDBC sessions. The SPA retrieves a session-backed, BREACH-protected CSRF token from `/auth/csrf`, sends the advertised header on unsafe methods including login and logout, and obtains a fresh token after login. Session IDs rotate on login. Session cookies are HttpOnly and SameSite=Lax; Secure is true by default and false only in documented local HTTP configuration. Idle expiry is 15 minutes; absolute expiry is eight hours. Authentication and resource access check current customer status.

Customer identity comes from the security principal. Role checks protect controllers; owner predicates protect customer queries. Passwords use BCrypt cost 12. A shared database login limiter caps both normalized identity and remote address at 20 attempts per five minutes. Tomcat's native forwarded-header handling is used behind the trusted ALB or local Nginx. Do not expose port 8080 directly to untrusted networks with forwarded header handling enabled.

Exports quote CSV delimiters and prepend an apostrophe to values that could be interpreted as spreadsheet formulas. Export count is capped at 10,000 and date ranges at 366 days. Notes and aliases are never interpolated into SQL. The application logs operational outcomes without logging credentials or memos.

## Design baseline and implementation scope

The provided low level design is a broader blueprint. This code implements its demo flow with these explicit choices: Spring JDBC instead of JPA; shared PostgreSQL sessions; manual observations as the expense source; direct in-app notifications instead of remote email delivery; customer and reviewer roles with seeded accounts. The ADMIN role is reserved in the schema; no admin console, rule-editing API, registration, password reset or account freeze UI is exposed. Rule thresholds can be changed by a reviewed migration that appends a new version. Existing versions must not be modified.

Posted purchase and refund journal types, expense classification of external bank transactions, reversals and bank integrations are outside this implementation. Consequently all current journal types are INTERNAL_TRANSFER and DEMO_FUNDING. The code does not claim to implement external payments or a regulated bank.

## Database ownership and operations

Flyway owns schema creation. The demo runtime database user also applies migrations; separate migrator and restricted runtime credentials are required before adapting this to a real service. Triggers are defense in depth, not a substitute for database role separation. Audit rows are append only within the runtime schema, not a cryptographically tamper-proof archive.

Never edit an applied migration. Add a new migration and test upgrades. Restore exercises, migration rollout tests, real load testing, MFA, recovery workflows, secret rotation automation and production threat assessment have not been completed by this generated project.

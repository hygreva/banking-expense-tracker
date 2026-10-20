CREATE TABLE app_user(id UUID PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
 display_name TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 timezone TEXT NOT NULL DEFAULT 'America/New_York', created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE user_role(user_id UUID REFERENCES app_user(id), role TEXT NOT NULL CHECK(role IN ('CUSTOMER','FRAUD_REVIEWER','ADMIN')), PRIMARY KEY(user_id,role));
CREATE TABLE fraud_subject(user_id UUID PRIMARY KEY REFERENCES app_user(id));
CREATE TABLE account(id UUID PRIMARY KEY, owner_id UUID REFERENCES app_user(id), display_name TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('CHECKING','SAVINGS','SYSTEM')), currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'),
 balance_minor BIGINT NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 recipient_ref UUID NOT NULL UNIQUE, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(owner_id IS NULL OR balance_minor >= 0), CHECK((owner_id IS NULL) = (kind='SYSTEM')));
CREATE INDEX account_owner ON account(owner_id,status);
CREATE TABLE beneficiary(id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), target_account_id UUID NOT NULL REFERENCES account(id),
 alias TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(user_id,target_account_id));
CREATE TABLE fraud_rule_set(version INT PRIMARY KEY, amount_review BIGINT NOT NULL CHECK(amount_review>0), velocity_count INT NOT NULL CHECK(velocity_count>0),
 daily_review BIGINT NOT NULL CHECK(daily_review>0), new_beneficiary_review BIGINT NOT NULL CHECK(new_beneficiary_review>0), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO fraud_rule_set VALUES(1,500000,5,1000000,100000,now());
CREATE TABLE transfer(id UUID PRIMARY KEY, initiator_id UUID NOT NULL REFERENCES app_user(id), source_id UUID NOT NULL REFERENCES account(id),
 destination_id UUID NOT NULL REFERENCES account(id), amount_minor BIGINT NOT NULL CHECK(amount_minor BETWEEN 1 AND 1000000), currency CHAR(3) NOT NULL CHECK(currency='USD'),
 memo VARCHAR(140) NOT NULL DEFAULT '', status TEXT NOT NULL CHECK(status IN ('POSTED','PENDING_REVIEW','REJECTED')),
 reason TEXT NOT NULL, rule_version INT NOT NULL REFERENCES fraud_rule_set(version), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), posted_at TIMESTAMPTZ,
 CHECK(source_id<>destination_id));
CREATE INDEX transfer_velocity ON transfer(initiator_id,created_at DESC);
CREATE TABLE journal(id UUID PRIMARY KEY, transfer_id UUID UNIQUE REFERENCES transfer(id), type TEXT NOT NULL CHECK(type IN ('INTERNAL_TRANSFER','DEMO_FUNDING')),
 currency CHAR(3) NOT NULL CHECK(currency='USD'), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE ledger_entry(id UUID PRIMARY KEY, journal_id UUID NOT NULL REFERENCES journal(id), account_id UUID NOT NULL REFERENCES account(id),
 amount_minor BIGINT NOT NULL CHECK(amount_minor<>0), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(journal_id,account_id));
CREATE INDEX entry_history ON ledger_entry(account_id,created_at DESC,id DESC);
CREATE FUNCTION validate_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE jid UUID; n INT; total NUMERIC; bad INT;
BEGIN
 jid := NEW.id;
 SELECT count(*),coalesce(sum(e.amount_minor),0) INTO n,total FROM ledger_entry e WHERE e.journal_id=jid;
 SELECT count(*) INTO bad FROM ledger_entry e JOIN account a ON a.id=e.account_id JOIN journal j ON j.id=e.journal_id WHERE e.journal_id=jid AND a.currency<>j.currency;
 IF n<2 OR total<>0 OR bad<>0 THEN RAISE EXCEPTION 'Invalid balanced journal %',jid USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER balanced_journal AFTER INSERT ON journal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_journal();
CREATE FUNCTION immutable_record() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Append only record' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_journal BEFORE UPDATE OR DELETE ON journal FOR EACH ROW EXECUTE FUNCTION immutable_record();
CREATE TRIGGER immutable_entry BEFORE UPDATE OR DELETE ON ledger_entry FOR EACH ROW EXECUTE FUNCTION immutable_record();
CREATE FUNCTION prevent_late_entry() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM journal WHERE id=NEW.journal_id AND xmin::text=pg_current_xact_id()::text) THEN RAISE EXCEPTION 'Journal already posted' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER no_late_entries BEFORE INSERT ON ledger_entry FOR EACH ROW EXECUTE FUNCTION prevent_late_entry();
CREATE TABLE idempotency_record(user_id UUID NOT NULL REFERENCES app_user(id), key UUID NOT NULL, request_hash TEXT NOT NULL, http_status INT NOT NULL,
 response_json JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(user_id,key));
CREATE TABLE fraud_case(id UUID PRIMARY KEY, transfer_id UUID NOT NULL UNIQUE REFERENCES transfer(id), decision TEXT NOT NULL DEFAULT 'PENDING',
 reviewer_id UUID REFERENCES app_user(id), reviewer_reason VARCHAR(500), version BIGINT NOT NULL DEFAULT 0, reviewed_at TIMESTAMPTZ);
CREATE TABLE category(id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES app_user(id), name VARCHAR(60) NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('EXPENSE','INCOME')), UNIQUE(owner_id,name,kind), UNIQUE(id,owner_id,kind));
CREATE TABLE manual_activity(id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), category_id UUID NOT NULL, kind TEXT NOT NULL,
 amount_minor BIGINT NOT NULL CHECK(amount_minor BETWEEN 1 AND 1000000000), currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'),
 occurred_at TIMESTAMPTZ NOT NULL, note VARCHAR(140) NOT NULL DEFAULT '', version BIGINT NOT NULL DEFAULT 0, deleted_at TIMESTAMPTZ,
 FOREIGN KEY(category_id,user_id,kind) REFERENCES category(id,owner_id,kind));
CREATE INDEX activity_period ON manual_activity(user_id,occurred_at) WHERE deleted_at IS NULL;
CREATE TABLE budget(id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), category_id UUID NOT NULL, kind TEXT NOT NULL DEFAULT 'EXPENSE' CHECK(kind='EXPENSE'),
 month_start DATE NOT NULL CHECK(extract(day from month_start)=1), currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'), limit_minor BIGINT NOT NULL CHECK(limit_minor BETWEEN 1 AND 1000000000),
 version BIGINT NOT NULL DEFAULT 0, FOREIGN KEY(category_id,user_id,kind) REFERENCES category(id,owner_id,kind), UNIQUE(user_id,category_id,month_start,currency));
CREATE TABLE budget_alert(budget_id UUID REFERENCES budget(id), threshold INT CHECK(threshold IN (80,100)), PRIMARY KEY(budget_id,threshold));
CREATE TABLE outbox_event(id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), type TEXT NOT NULL, message TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), delivered_at TIMESTAMPTZ);
CREATE INDEX outbox_pending ON outbox_event(created_at) WHERE delivered_at IS NULL;
CREATE TABLE notification(event_id UUID PRIMARY KEY REFERENCES outbox_event(id), user_id UUID NOT NULL REFERENCES app_user(id), message TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE audit_event(id UUID PRIMARY KEY, actor_id UUID REFERENCES app_user(id), action TEXT NOT NULL, resource_id UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION immutable_record();
CREATE TABLE login_bucket(key TEXT PRIMARY KEY, window_start TIMESTAMPTZ NOT NULL, attempts INT NOT NULL);
CREATE TABLE spring_session(PRIMARY_ID CHAR(36) NOT NULL PRIMARY KEY, SESSION_ID CHAR(36) NOT NULL, CREATION_TIME BIGINT NOT NULL,
 LAST_ACCESS_TIME BIGINT NOT NULL, MAX_INACTIVE_INTERVAL INT NOT NULL, EXPIRY_TIME BIGINT NOT NULL, PRINCIPAL_NAME VARCHAR(100));
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session(SESSION_ID);
CREATE INDEX spring_session_ix2 ON spring_session(EXPIRY_TIME);
CREATE INDEX spring_session_ix3 ON spring_session(PRINCIPAL_NAME);
CREATE TABLE spring_session_attributes(SESSION_PRIMARY_ID CHAR(36) NOT NULL REFERENCES spring_session(PRIMARY_ID) ON DELETE CASCADE,
 ATTRIBUTE_NAME VARCHAR(200) NOT NULL, ATTRIBUTE_BYTES BYTEA NOT NULL, PRIMARY KEY(SESSION_PRIMARY_ID,ATTRIBUTE_NAME));

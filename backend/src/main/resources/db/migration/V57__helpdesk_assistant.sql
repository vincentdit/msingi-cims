-- The helpdesk assistant (FR-COM-08): CIMS predicts answers from the institution's own published answers and from requests
-- that were solved, without any outside service. It suggests answers while people type, guesses the category, answers at once
-- when it is confident, suggests replies to staff, learns from solved requests and lists frequent questions with no answer.

-- an answer given by the assistant is a message of its own, linked to the answer (article) it came from
ALTER TABLE helpdesk_messages DROP CONSTRAINT helpdesk_messages_author_type_check;
ALTER TABLE helpdesk_messages ADD CONSTRAINT helpdesk_messages_author_type_check
    CHECK (author_type IN ('REQUESTER', 'STAFF', 'SYSTEM', 'ASSISTANT'));
ALTER TABLE helpdesk_messages ADD COLUMN article_id BIGINT REFERENCES helpdesk_articles (id) ON DELETE SET NULL;

-- what the assistant predicted when the request was raised, to measure it and to learn
ALTER TABLE helpdesk_tickets
    ADD COLUMN predicted_category_id  BIGINT REFERENCES helpdesk_categories (id) ON DELETE SET NULL,
    ADD COLUMN category_confidence    NUMERIC(4,3),
    ADD COLUMN suggested_article_id   BIGINT REFERENCES helpdesk_articles (id) ON DELETE SET NULL,
    ADD COLUMN suggestion_score       NUMERIC(4,3),
    -- INSTANT: shown on the request at once; then ACCEPTED (it solved it) or REJECTED (still needs help)
    ADD COLUMN suggestion_outcome     VARCHAR(10) CHECK (suggestion_outcome IN ('INSTANT', 'ACCEPTED', 'REJECTED'));

-- replies that solved a request (the requester said so, or did not reopen it): staff see them as suggested replies
CREATE TABLE helpdesk_learned_answers (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    ticket_id     BIGINT        NOT NULL UNIQUE REFERENCES helpdesk_tickets (id) ON DELETE CASCADE,
    category_id   BIGINT        NOT NULL REFERENCES helpdesk_categories (id),
    question      TEXT          NOT NULL,
    answer        TEXT          NOT NULL,
    confirmed     BOOLEAN       NOT NULL,               -- the requester said the answer helped
    used          INTEGER       NOT NULL DEFAULT 0,     -- times staff used it as a reply
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_helpdesk_learned_category ON helpdesk_learned_answers (tenant_id, category_id);

-- questions answered by a suggested answer before anyone asked: a request that did not have to be raised
CREATE TABLE helpdesk_deflections (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    article_id    BIGINT        REFERENCES helpdesk_articles (id) ON DELETE SET NULL,
    audience      VARCHAR(10)   NOT NULL CHECK (audience IN ('APPLICANT', 'STUDENT', 'STAFF', 'PUBLIC')),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_helpdesk_deflections_time ON helpdesk_deflections (tenant_id, created_at);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['helpdesk_learned_answers', 'helpdesk_deflections']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;

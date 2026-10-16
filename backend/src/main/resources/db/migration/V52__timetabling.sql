-- Timetabling (FR-TCH-05, FR-EXM-15): teaching venues, the weekly class timetable of a semester, the examination timetable,
-- and when each was published to students.

CREATE TABLE teaching_venues (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL REFERENCES tenants (id),
    campus_id      BIGINT        REFERENCES campuses (id),
    code           VARCHAR(20)   NOT NULL,
    name           VARCHAR(120)  NOT NULL,
    kind           VARCHAR(14)   NOT NULL CHECK (kind IN ('LECTURE_HALL', 'CLASSROOM', 'LABORATORY', 'COMPUTER_LAB', 'WORKSHOP', 'OTHER')),
    capacity       INTEGER       NOT NULL CHECK (capacity > 0),
    exam_capacity  INTEGER       CHECK (exam_capacity > 0),       -- candidates seated for an examination (spaced); the capacity if empty
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_teaching_venues UNIQUE (tenant_id, code)
);

-- A weekly class of a module offering: day, time, venue, and the lecturer (any of the offering's lecturers if empty)
CREATE TABLE timetable_slots (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    offering_id       BIGINT        NOT NULL REFERENCES module_offerings (id) ON DELETE CASCADE,
    kind              VARCHAR(10)   NOT NULL CHECK (kind IN ('LECTURE', 'TUTORIAL', 'PRACTICAL', 'SEMINAR')),
    day_of_week       SMALLINT      NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),       -- ISO: 1 Monday … 7 Sunday
    start_time        TIME          NOT NULL,
    end_time          TIME          NOT NULL,
    venue_id          BIGINT        NOT NULL REFERENCES teaching_venues (id),
    lecturer_user_id  BIGINT        REFERENCES user_accounts (id),
    group_label       VARCHAR(30),                                                       -- a part of the class (e.g. Practical group A)
    note              VARCHAR(200),
    changed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),                              -- last change students should know about
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CHECK (end_time > start_time)
);
CREATE INDEX ix_timetable_slots_offering ON timetable_slots (offering_id);
CREATE INDEX ix_timetable_slots_venue ON timetable_slots (tenant_id, venue_id, day_of_week);

-- An examination paper of a module offering: date, time, the venues it is sat in and the invigilators
CREATE TABLE exam_sittings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    offering_id   BIGINT        NOT NULL REFERENCES module_offerings (id) ON DELETE CASCADE,
    paper         VARCHAR(40)   NOT NULL DEFAULT 'Paper 1',
    sitting_date  DATE          NOT NULL,
    start_time    TIME          NOT NULL,
    end_time      TIME          NOT NULL,
    note          VARCHAR(200),
    changed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),                                  -- last change (including venues) students should know about
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CHECK (end_time > start_time),
    CONSTRAINT uq_exam_sittings UNIQUE (offering_id, paper)
);
CREATE INDEX ix_exam_sittings_date ON exam_sittings (tenant_id, sitting_date);

CREATE TABLE exam_sitting_venues (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT  NOT NULL REFERENCES tenants (id),
    sitting_id  BIGINT  NOT NULL REFERENCES exam_sittings (id) ON DELETE CASCADE,
    venue_id    BIGINT  NOT NULL REFERENCES teaching_venues (id),
    CONSTRAINT uq_exam_sitting_venues UNIQUE (sitting_id, venue_id)
);

CREATE TABLE exam_invigilators (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT  NOT NULL REFERENCES tenants (id),
    sitting_id  BIGINT  NOT NULL REFERENCES exam_sittings (id) ON DELETE CASCADE,
    user_id     BIGINT  NOT NULL REFERENCES user_accounts (id),
    CONSTRAINT uq_exam_invigilators UNIQUE (sitting_id, user_id)
);

-- Students see a timetable once it is published; changes made afterwards are marked as changed
ALTER TABLE semesters
    ADD COLUMN class_timetable_published_at    TIMESTAMPTZ,
    ADD COLUMN class_timetable_published_by    VARCHAR(150),
    ADD COLUMN exam_timetable_published_at     TIMESTAMPTZ,
    ADD COLUMN exam_timetable_published_by     VARCHAR(150);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['teaching_venues', 'timetable_slots', 'exam_sittings', 'exam_sitting_venues', 'exam_invigilators']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;

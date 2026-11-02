-- ClassOS V5: user feedback for Milestone 5 (evaluation and refinement).

-- Free-text feedback from the in-app button. role is copied so results can be analysed
-- by role even if a user's role changes later.
CREATE TABLE feedback (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role        VARCHAR(20)  NOT NULL,
    page        VARCHAR(200),
    category    VARCHAR(10)  NOT NULL CONSTRAINT chk_feedback_category CHECK (category IN ('BUG', 'IDEA', 'PRAISE', 'OTHER')),
    rating      SMALLINT     CONSTRAINT chk_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    message     TEXT         NOT NULL CONSTRAINT chk_feedback_length CHECK (length(message) BETWEEN 1 AND 4000),
    status      VARCHAR(10)  NOT NULL DEFAULT 'NEW' CONSTRAINT chk_feedback_status CHECK (status IN ('NEW', 'REVIEWED', 'DONE')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_feedback_created ON feedback (created_at DESC);

-- System Usability Scale: 10 answers on a 1-5 scale, one response per user (updatable).
CREATE TABLE sus_responses (
    user_id       BIGINT        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    role          VARCHAR(20)   NOT NULL,
    answers       SMALLINT[]    NOT NULL CONSTRAINT chk_sus_answers
        CHECK (array_length(answers, 1) = 10 AND 1 <= ALL (answers) AND 5 >= ALL (answers)),
    score         NUMERIC(5, 2) NOT NULL CONSTRAINT chk_sus_score CHECK (score BETWEEN 0 AND 100),
    comment       TEXT          CONSTRAINT chk_sus_comment CHECK (comment IS NULL OR length(comment) <= 4000),
    submitted_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

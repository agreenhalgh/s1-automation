-- step_result: one row per step EXECUTION, not per step.
--
-- Why by hand: the schema is still created by Hibernate's ddl-auto: update, which adds columns
-- but never drops a constraint. The old unique constraint on (run_id, step_index) is exactly what
-- has to go — under a conditional flow a run can visit one step several times, and that constraint
-- makes the second visit either overwrite the first or fail outright. ddl-auto cannot get there on
-- its own, so this runs once against each existing database.
--
--   docker exec -i systmone-postgres psql -U systmone -d systmone \
--     < docker/migrations/2026-08-21-step-result-per-execution.sql
--
-- Safe to run twice; every statement is conditional or idempotent.

BEGIN;

-- 1. The constraint that made a step's position its identity.
ALTER TABLE step_result DROP CONSTRAINT IF EXISTS uq_step_run_index;

-- 2. The execution ordinal within a run, counting from 1.
ALTER TABLE step_result ADD COLUMN IF NOT EXISTS execution_seq integer;

-- 3. Backfill. Rows written before conditional flows existed were one execution each, and the old
--    unique constraint guarantees their step_index is distinct within a run — so it is both a
--    usable sequence and a unique one. Those historical rows therefore count from 0 where new
--    ones count from 1, which affects nothing: the sequence only has to order and identify
--    executions within a single run.
UPDATE step_result SET execution_seq = step_index WHERE execution_seq IS NULL;

ALTER TABLE step_result ALTER COLUMN execution_seq SET NOT NULL;

-- 4. The new identity: one row per execution of a run.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_step_run_sequence') THEN
        ALTER TABLE step_result ADD CONSTRAINT uq_step_run_sequence UNIQUE (run_id, execution_seq);
    END IF;
END $$;

COMMIT;

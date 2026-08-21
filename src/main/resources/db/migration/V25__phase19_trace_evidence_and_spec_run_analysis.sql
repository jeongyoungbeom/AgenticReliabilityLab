-- Phase 19: keep the timeline a verdict was based on, and let a specification run be analyzed.
--
-- Until now a trial stored only the rendered display strings of its observed values, so any span past the fifth
-- was gone forever. A verdict could say "the deduction landed 340ms late" while the evidence for it no longer
-- existed anywhere - which is exactly the evidence an improvement suggestion has to reason from.
alter table test_spec_trial_result add column observations_json text;

-- A specification run becomes a third kind of analysis input, alongside experiments and Target test batches.
alter table analysis_dataset add column test_spec_run_id uuid;
alter table analysis_dataset add constraint fk_analysis_dataset_test_spec_run
    foreign key (test_spec_run_id) references test_spec_run (id);
alter table analysis_dataset drop constraint ck_analysis_dataset_source;
alter table analysis_dataset add constraint ck_analysis_dataset_source
    check ((case when experiment_run_id is not null then 1 else 0 end)
         + (case when target_test_batch_id is not null then 1 else 0 end)
         + (case when test_spec_run_id is not null then 1 else 0 end) = 1);
create index idx_analysis_dataset_test_spec_run
    on analysis_dataset (test_spec_run_id, created_at);

-- An analysis run may now identify its input by dataset alone.
--
-- The two source columns on analysis_run are denormalized copies kept for idempotency lookups; the dataset itself
-- has always been the real input, and analysis_dataset_id has pointed at it since V6. Adding a third copy here
-- would spread the same denormalization into analysis_comparison, follow_up_suggestion and their DTOs for no gain,
-- so a specification run's analysis carries the dataset and nothing else.
alter table analysis_run drop constraint ck_analysis_run_source;
alter table analysis_run add constraint ck_analysis_run_source
    check ((experiment_run_id is not null and target_test_batch_id is null)
        or (experiment_run_id is null and target_test_batch_id is not null)
        or (experiment_run_id is null and target_test_batch_id is null and analysis_dataset_id is not null));
-- One idempotency key means one run per dataset, matching what the two source-scoped indexes already enforce.
-- A comparison gives each model its own key ("comparison:<id>:<selection>"), so several models on one dataset
-- do not collide. No partial index: H2 does not support one, and the rule is correct unconditionally.
create unique index uq_analysis_run_dataset_idempotency
    on analysis_run (analysis_dataset_id, idempotency_key);

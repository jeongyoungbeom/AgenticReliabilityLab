alter table workload_lease drop constraint ck_workload_lease_mode;
alter table workload_lease add constraint ck_workload_lease_mode
    check (mode in ('EXPERIMENT_WINDOW', 'LOCAL_LLM_WINDOW', 'TARGET_HTTP_BATCH'));

alter table analysis_dataset alter column experiment_run_id drop not null;
alter table analysis_dataset add column target_test_batch_id varchar(36);
alter table analysis_dataset add constraint fk_analysis_dataset_target_test_batch
    foreign key (target_test_batch_id) references target_test_batch (id);
alter table analysis_dataset add constraint ck_analysis_dataset_source
    check ((experiment_run_id is not null and target_test_batch_id is null)
        or (experiment_run_id is null and target_test_batch_id is not null));
create index idx_analysis_dataset_target_test_batch
    on analysis_dataset (target_test_batch_id, created_at);

alter table analysis_run alter column experiment_run_id drop not null;
alter table analysis_run add column target_test_batch_id varchar(36);
alter table analysis_run add constraint fk_analysis_run_target_test_batch
    foreign key (target_test_batch_id) references target_test_batch (id);
alter table analysis_run add constraint ck_analysis_run_source
    check ((experiment_run_id is not null and target_test_batch_id is null)
        or (experiment_run_id is null and target_test_batch_id is not null));
create unique index uq_analysis_run_target_test_batch_idempotency
    on analysis_run (target_test_batch_id, idempotency_key);
create index idx_analysis_run_target_test_batch
    on analysis_run (target_test_batch_id, requested_at);

alter table analysis_comparison alter column experiment_run_id drop not null;
alter table analysis_comparison add column target_test_batch_id varchar(36);
alter table analysis_comparison add constraint fk_analysis_comparison_target_test_batch
    foreign key (target_test_batch_id) references target_test_batch (id);
alter table analysis_comparison add constraint ck_analysis_comparison_source
    check ((experiment_run_id is not null and target_test_batch_id is null)
        or (experiment_run_id is null and target_test_batch_id is not null));
create unique index uq_analysis_comparison_target_test_batch_idempotency
    on analysis_comparison (target_test_batch_id, idempotency_key);

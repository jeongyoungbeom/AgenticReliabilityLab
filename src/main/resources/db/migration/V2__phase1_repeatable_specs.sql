alter table planned_run_spec
    drop constraint uq_planned_run_spec_hash;

create index idx_planned_run_spec_hash
    on planned_run_spec (spec_hash);

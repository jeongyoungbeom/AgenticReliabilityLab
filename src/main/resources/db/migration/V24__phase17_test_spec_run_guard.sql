alter table test_spec_run add column active_slot varchar(20);

update test_spec_run
set status = 'FAILED', cleanup_verified = true, completed_at = current_timestamp,
    failure = 'Application restarted before Target execution was claimed'
where status = 'PENDING';

update test_spec_run
set status = 'RECOVERY_REQUIRED', cleanup_verified = false, completed_at = current_timestamp,
    failure = 'Application restarted while Target requests could have been in progress'
where status = 'RUNNING';

update test_spec_run
set active_slot = 'ACTIVE'
where id in (
    select id
    from (
        select id, row_number() over (partition by target_system_id order by created_at, id) as slot_order
        from test_spec_run
        where status = 'RECOVERY_REQUIRED'
    ) ranked_recovery
    where slot_order = 1
);

alter table test_spec_run add constraint ck_test_spec_run_active_slot check (active_slot is null or active_slot = 'ACTIVE');
alter table test_spec_run add constraint uq_test_spec_run_active_slot unique (target_system_id, active_slot);

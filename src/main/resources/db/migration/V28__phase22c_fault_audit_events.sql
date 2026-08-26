alter table test_spec_trial_result
    add column fault_events_json text not null default '[]';

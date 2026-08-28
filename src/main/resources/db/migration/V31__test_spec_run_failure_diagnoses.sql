alter table test_spec_run add column diagnostic_stage varchar(30);
alter table test_spec_run add column diagnostic_summary varchar(500);
alter table test_spec_run add column diagnostic_likely_cause varchar(1000);
alter table test_spec_run add column diagnostic_next_action varchar(1000);
alter table test_spec_run add column diagnostic_technical_detail varchar(500);

alter table test_spec_trial_result add column diagnostic_stage varchar(30);
alter table test_spec_trial_result add column diagnostic_summary varchar(500);
alter table test_spec_trial_result add column diagnostic_likely_cause varchar(1000);
alter table test_spec_trial_result add column diagnostic_next_action varchar(1000);
alter table test_spec_trial_result add column diagnostic_technical_detail varchar(500);

alter table test_spec_reset_result add column diagnostic_stage varchar(30);
alter table test_spec_reset_result add column diagnostic_summary varchar(500);
alter table test_spec_reset_result add column diagnostic_likely_cause varchar(1000);
alter table test_spec_reset_result add column diagnostic_next_action varchar(1000);
alter table test_spec_reset_result add column diagnostic_technical_detail varchar(500);

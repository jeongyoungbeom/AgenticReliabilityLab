alter table pilot_test_session add column diagnostic_stage varchar(30);
alter table pilot_test_session add column diagnostic_summary varchar(500);
alter table pilot_test_session add column diagnostic_likely_cause varchar(1000);
alter table pilot_test_session add column diagnostic_next_action varchar(1000);
alter table pilot_test_session add column diagnostic_technical_detail varchar(1000);

alter table pilot_test_session_item add column diagnostic_stage varchar(30);
alter table pilot_test_session_item add column diagnostic_summary varchar(500);
alter table pilot_test_session_item add column diagnostic_likely_cause varchar(1000);
alter table pilot_test_session_item add column diagnostic_next_action varchar(1000);
alter table pilot_test_session_item add column diagnostic_technical_detail varchar(1000);

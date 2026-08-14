alter table target_test_batch
    add column approved_by varchar(200);

alter table target_test_batch
    add column approval_correlation_id varchar(100);

alter table failure_injection_plan
    add column approved_by varchar(200);

alter table failure_injection_plan
    add column approval_correlation_id varchar(100);

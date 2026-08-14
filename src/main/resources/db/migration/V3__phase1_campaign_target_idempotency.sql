alter table campaign_run
    add column target_system_id varchar(100);

alter table campaign_run
    add column idempotency_key varchar(200);

alter table campaign_run
    add column parameters_json text;

alter table campaign_run
    add column repeat_count integer;

alter table campaign_run
    add constraint fk_campaign_run_target
    foreign key (target_system_id) references target_system (id);

create unique index uq_campaign_run_target_idempotency
    on campaign_run (target_system_id, idempotency_key);

insert into campaign_definition (
    id, name, definition_version, timezone, status, definition_json, created_at
) values (
    'stock-concurrency-repeat-v1',
    'stock-concurrency-repeat',
    1,
    'UTC',
    'ENABLED',
    '{"type":"STOCK_CONCURRENCY","execution":"SEQUENTIAL_REPEAT"}',
    current_timestamp
);

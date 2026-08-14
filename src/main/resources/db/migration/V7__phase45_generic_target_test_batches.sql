create table target_test_batch (
    id varchar(36) primary key,
    target_system_id varchar(100) not null,
    idempotency_key varchar(200) not null,
    request_hash varchar(128) not null,
    status varchar(40) not null,
    approved_at timestamp with time zone,
    queued_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    failure_message varchar(1000),
    constraint fk_target_test_batch_target
        foreign key (target_system_id) references target_system (id),
    constraint uq_target_test_batch_idempotency
        unique (target_system_id, idempotency_key)
);

create table target_test_batch_item (
    id varchar(36) primary key,
    batch_id varchar(36) not null,
    candidate_id varchar(100) not null,
    sequence_number integer not null,
    candidate_kind varchar(60) not null,
    title varchar(200) not null,
    method varchar(10) not null,
    path varchar(1000) not null,
    expected_status_codes varchar(1000) not null,
    timeout_millis bigint not null,
    status varchar(40) not null,
    http_status integer,
    latency_millis bigint,
    result_json text,
    failure_message varchar(1000),
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    constraint fk_target_test_batch_item_batch
        foreign key (batch_id) references target_test_batch (id),
    constraint uq_target_test_batch_item_candidate
        unique (batch_id, candidate_id),
    constraint uq_target_test_batch_item_sequence
        unique (batch_id, sequence_number)
);

create index idx_target_test_batch_status
    on target_test_batch (status, queued_at);

create index idx_target_test_batch_item_status
    on target_test_batch_item (batch_id, status, sequence_number);

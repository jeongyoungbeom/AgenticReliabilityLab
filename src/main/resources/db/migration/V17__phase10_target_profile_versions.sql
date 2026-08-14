create table target_profile_version (
    id uuid primary key,
    target_system_id varchar(100) not null,
    source varchar(30) not null,
    status varchar(30) not null,
    checksum varchar(64) not null,
    config_json text not null,
    created_by varchar(200) not null,
    created_at timestamp with time zone not null,
    activated_by varchar(200),
    activated_at timestamp with time zone,
    constraint ck_target_profile_version_source
        check (source in ('BOOTSTRAP', 'USER_IMPORT')),
    constraint ck_target_profile_version_status
        check (status in ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    constraint uq_target_profile_version_checksum
        unique (target_system_id, checksum)
);

create table target_profile_active (
    target_system_id varchar(100) primary key,
    profile_version_id uuid not null unique,
    activated_by varchar(200) not null,
    activated_at timestamp with time zone not null,
    constraint fk_target_profile_active_target
        foreign key (target_system_id) references target_system (id),
    constraint fk_target_profile_active_version
        foreign key (profile_version_id) references target_profile_version (id)
);

create table target_profile_audit_event (
    id uuid primary key,
    target_system_id varchar(100) not null,
    profile_version_id uuid not null,
    event_type varchar(30) not null,
    actor varchar(200) not null,
    correlation_id varchar(100) not null,
    occurred_at timestamp with time zone not null,
    constraint ck_target_profile_audit_event_type
        check (event_type in ('IMPORTED', 'ACTIVATED')),
    constraint fk_target_profile_audit_event_version
        foreign key (profile_version_id) references target_profile_version (id)
);

alter table target_test_batch
    add column profile_version_id uuid;

alter table target_test_batch
    add constraint fk_target_test_batch_profile_version
    foreign key (profile_version_id) references target_profile_version (id);

create index idx_target_profile_active_version
    on target_profile_active (profile_version_id);

create index idx_target_profile_audit_event_version
    on target_profile_audit_event (profile_version_id, occurred_at);

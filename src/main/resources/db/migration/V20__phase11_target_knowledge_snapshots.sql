create table target_knowledge_snapshot (
    id uuid primary key,
    target_system_id varchar(100) not null,
    profile_version_id uuid not null,
    checksum varchar(64) not null,
    extraction_version varchar(50) not null,
    content_json text not null,
    created_by varchar(200) not null,
    created_correlation_id varchar(100) not null,
    created_at timestamp with time zone not null,
    confirmed_by varchar(200),
    confirmed_correlation_id varchar(100),
    confirmed_at timestamp with time zone,
    constraint fk_target_knowledge_snapshot_profile_version
        foreign key (profile_version_id) references target_profile_version (id),
    constraint uq_target_knowledge_snapshot_checksum
        unique (profile_version_id, checksum)
);

create index idx_target_knowledge_snapshot_target
    on target_knowledge_snapshot (target_system_id, created_at);

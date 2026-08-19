create table test_candidate_generation (
    id uuid primary key,
    target_system_id varchar(100) not null,
    knowledge_snapshot_id uuid not null,
    profile_version_id uuid not null,
    source varchar(30) not null,
    generator_version varchar(50) not null,
    checksum varchar(64) not null,
    created_by varchar(200) not null,
    created_correlation_id varchar(100) not null,
    created_at timestamp with time zone not null,
    constraint ck_test_candidate_generation_source
        check (source in ('SNAPSHOT_RULES', 'DIRECT_REQUEST')),
    constraint fk_test_candidate_generation_snapshot
        foreign key (knowledge_snapshot_id) references target_knowledge_snapshot (id),
    constraint fk_test_candidate_generation_profile_version
        foreign key (profile_version_id) references target_profile_version (id),
    constraint uq_test_candidate_generation_checksum
        unique (knowledge_snapshot_id, checksum)
);

create table test_candidate (
    id uuid primary key,
    generation_id uuid not null,
    sequence_number integer not null,
    category varchar(30) not null,
    title varchar(200) not null,
    description varchar(1000) not null,
    risk varchar(20) not null,
    confidence varchar(20) not null,
    binding_kind varchar(30) not null,
    detail_json text not null,
    constraint ck_test_candidate_binding_kind
        check (binding_kind in ('READ_ONLY_BATCH', 'EXPERIMENT', 'UNBOUND')),
    constraint fk_test_candidate_generation
        foreign key (generation_id) references test_candidate_generation (id),
    constraint uq_test_candidate_sequence
        unique (generation_id, sequence_number)
);

create index idx_test_candidate_generation_target
    on test_candidate_generation (target_system_id, created_at);

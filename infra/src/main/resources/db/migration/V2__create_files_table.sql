create table files (
    id                uuid         not null,
    owner_user_id     uuid         not null,
    -- Reserved for the project aggregate, which does not exist yet.
    project_id        uuid,
    original_filename varchar(255) not null,
    storage_key       varchar(512) not null,
    format            varchar(32)  not null,
    content_type      varchar(255) not null,
    size_bytes        bigint       not null,
    checksum_sha256   varchar(64)  not null,
    status            varchar(32)  not null,
    version           bigint       not null,
    created_at        timestamp with time zone not null,
    updated_at        timestamp with time zone not null,
    deleted_at        timestamp with time zone,

    constraint pk_files               primary key (id),
    constraint uq_files_storage_key   unique (storage_key),
    constraint fk_files_owner_user_id foreign key (owner_user_id) references users (id),
    constraint ck_files_size_bytes    check (size_bytes > 0),
    constraint ck_files_version       check (version >= 0),
    constraint ck_files_status        check (status in ('AVAILABLE', 'DELETED'))
);


create index ix_files_owner_created on files (owner_user_id, created_at, id);
create index ix_files_project on files (project_id);
create index ix_files_owner_checksum on files (owner_user_id, checksum_sha256);

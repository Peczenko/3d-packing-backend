create table projects (
    id         uuid         not null,
    name       varchar(128) not null,
    created_by uuid         not null,
    status     varchar(32)  not null,
    -- Optimistic lock, as on users and files. Membership changes bump it too: the member
    -- rows are reconciled against the version the aggregate was read at.
    version    bigint       not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted_at timestamp with time zone,

    constraint pk_projects            primary key (id),
    constraint fk_projects_created_by foreign key (created_by) references users (id),
    constraint ck_projects_version    check (version >= 0),
    constraint ck_projects_status     check (status in ('ACTIVE', 'DISABLED', 'DELETED'))
);


-- created_by carries no authorisation weight: it records who opened the project. Access is
-- decided solely by project_members, where the creator starts as the first OWNER.
create table project_members (
    project_id uuid        not null,
    user_id    uuid        not null,
    permission varchar(16) not null,
    added_by   uuid        not null,
    added_at   timestamp with time zone not null,

    constraint pk_project_members            primary key (project_id, user_id),
    constraint fk_project_members_project    foreign key (project_id) references projects (id),
    constraint fk_project_members_user       foreign key (user_id)    references users (id),
    constraint fk_project_members_added_by   foreign key (added_by)   references users (id),
    constraint ck_project_members_permission check (permission in ('READ', 'WRITE', 'OWNER'))
);


-- The primary key already serves "members of project X". This serves the other direction,
-- "projects of user Y", which is both the project listing and the per-request permission
-- lookup on every file operation.
create index ix_project_members_user on project_members (user_id);

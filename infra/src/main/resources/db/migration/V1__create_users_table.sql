create table users (
    id            uuid         not null,
    firebase_uid  varchar(128) not null,
    email         varchar(320) not null,
    username      varchar(64)  not null,
    display_name  varchar(128),
    role          varchar(32)  not null,
    status        varchar(32)  not null,
    -- Optimistic lock. Aggregate writes carry the version they read and bump it, so a
    -- write based on a stale read fails rather than clobbering a concurrent change.
    version       bigint       not null,
    created_at    timestamp with time zone not null,
    updated_at    timestamp with time zone not null,
    last_login_at timestamp with time zone,

    constraint pk_users              primary key (id),
    constraint uq_users_firebase_uid unique (firebase_uid),
    constraint uq_users_email        unique (email),
    constraint uq_users_username     unique (username)
);

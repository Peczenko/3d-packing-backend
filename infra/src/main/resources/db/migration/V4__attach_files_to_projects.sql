-- Files were owner-scoped before projects existed. Every surviving row is given a project
-- so that project_id can become mandatory: one "Personal" project per distinct owner, with
-- that owner as its first OWNER.
--
-- The whole backfill is one data-modifying CTE, so it cannot half-apply and needs no marker
-- column to find the rows it just inserted. It is PostgreSQL-only and it is DML, so it is
-- hidden from jOOQ's DDLDatabase, which would otherwise replay it against H2 during offline
-- code generation. The ALTER statements below stay visible on purpose: the generator has to
-- see the NOT NULL to emit a non-nullable FILES.PROJECT_ID.
-- [jooq ignore start]
with created as (
    insert into projects (id, name, created_by, status, version, created_at, updated_at)
    select gen_random_uuid(), 'Personal', o.owner_user_id, 'ACTIVE', 0, now(), now()
    from (select distinct owner_user_id from files) o
    returning id, created_by
),
membership as (
    insert into project_members (project_id, user_id, permission, added_by, added_at)
    select id, created_by, 'OWNER', created_by, now()
    from created
)
update files f
set project_id = c.id
from created c
where c.created_by = f.owner_user_id;
-- [jooq ignore stop]


alter table files alter column project_id set not null;

alter table files add constraint fk_files_project_id
    foreign key (project_id) references projects (id);


-- Superseded by the composite below: listings are ordered by (created_at, id) within one
-- project, so the single-column index can no longer serve them.
--
-- Hidden from the generator because jOOQ's DDL simulation cannot translate a bare DROP
-- INDEX to H2. Safe to hide: an index is not part of the generated table or record types,
-- unlike a CREATE TABLE or ADD COLUMN, which must never be wrapped this way.
-- [jooq ignore start]
drop index ix_files_project;
-- [jooq ignore stop]

create index ix_files_project_created on files (project_id, created_at, id);

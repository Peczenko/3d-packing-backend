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


alter table files alter column project_id set not null;

alter table files add constraint fk_files_project_id
    foreign key (project_id) references projects (id);

drop index ix_files_project;

create index ix_files_project_created on files (project_id, created_at, id);

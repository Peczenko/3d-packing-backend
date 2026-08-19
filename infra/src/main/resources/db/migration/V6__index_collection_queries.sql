CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_projects_name_trgm
    ON projects USING gin ((lower(name)) gin_trgm_ops);

CREATE INDEX ix_files_filename_trgm
    ON files USING gin ((lower(original_filename)) gin_trgm_ops);

CREATE INDEX ix_packing_jobs_search_trgm
    ON packing_jobs USING gin ((
        lower(
            coalesce(engine_version, '')
            || ' '
            || coalesce(result_file_name, '')
            || ' '
            || coalesce(failure_reason, '')
        )
    ) gin_trgm_ops);

CREATE INDEX ix_users_username_trgm
    ON users USING gin ((lower(username)) gin_trgm_ops);

CREATE INDEX ix_users_display_name_trgm
    ON users USING gin ((lower(display_name)) gin_trgm_ops)
    WHERE display_name IS NOT NULL;

CREATE INDEX ix_project_members_project_added
    ON project_members(project_id, added_at, user_id);

CREATE INDEX ix_packing_jobs_project_started
    ON packing_jobs(project_id, started_at, id)
    WHERE started_at IS NOT NULL;

CREATE INDEX ix_packing_jobs_project_finished
    ON packing_jobs(project_id, finished_at, id)
    WHERE finished_at IS NOT NULL;

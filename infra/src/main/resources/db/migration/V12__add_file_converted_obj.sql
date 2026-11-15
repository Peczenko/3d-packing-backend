ALTER TABLE files
    ADD COLUMN converted_storage_key     VARCHAR(512),
    ADD COLUMN converted_size_bytes      BIGINT,
    ADD COLUMN converted_checksum_sha256 VARCHAR(64);

ALTER TABLE files ADD CONSTRAINT uq_files_converted_storage_key UNIQUE (converted_storage_key);

ALTER TABLE files ADD CONSTRAINT ck_files_converted_complete
    CHECK ((converted_storage_key IS NULL) = (converted_size_bytes IS NULL)
       AND (converted_storage_key IS NULL) = (converted_checksum_sha256 IS NULL));

ALTER TABLE files ADD CONSTRAINT ck_files_converted_size_bytes
    CHECK (converted_size_bytes > 0);

ALTER TABLE files ADD CONSTRAINT ck_files_obj_not_converted
    CHECK (format <> 'OBJ' OR converted_storage_key IS NULL);

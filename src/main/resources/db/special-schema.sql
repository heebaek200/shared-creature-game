CREATE TABLE IF NOT EXISTS spring_session (
    primary_id            CHAR(36)     NOT NULL
  , session_id            CHAR(36)     NOT NULL
  , creation_time         BIGINT       NOT NULL
  , last_access_time      BIGINT       NOT NULL
  , max_inactive_interval INT          NOT NULL
  , expiry_time           BIGINT       NOT NULL
  , principal_name        VARCHAR(100) NULL
  , PRIMARY KEY (primary_id)
  , UNIQUE KEY uq_spring_session_id (session_id)
  , KEY ix_spring_session_expiry (expiry_time)
  , KEY ix_spring_session_principal (principal_name)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS spring_session_attributes (
    session_primary_id CHAR(36)     NOT NULL
  , attribute_name     VARCHAR(200) NOT NULL
  , attribute_bytes    LONGBLOB     NOT NULL
  , PRIMARY KEY (session_primary_id, attribute_name)
  , CONSTRAINT fk_spring_session_attributes
        FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

SET @ddl_statement = (
    SELECT IF(
        EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = DATABASE()
               AND table_name = 'group_memberships'
               AND column_name = 'active_user_id'
               AND extra LIKE '%STORED GENERATED%'
        )
      , 'SELECT 1'
      , 'ALTER TABLE group_memberships MODIFY COLUMN active_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = ''ACTIVE'' THEN user_id ELSE NULL END) STORED'
    )
);
PREPARE ddl_command FROM @ddl_statement;
EXECUTE ddl_command;
DEALLOCATE PREPARE ddl_command;

SET @ddl_statement = (
    SELECT IF(
        EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = DATABASE()
               AND table_name = 'group_memberships'
               AND column_name = 'primary_group_id'
               AND extra LIKE '%STORED GENERATED%'
        )
      , 'SELECT 1'
      , 'ALTER TABLE group_memberships MODIFY COLUMN primary_group_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = ''ACTIVE'' AND role = ''PRIMARY'' THEN group_id ELSE NULL END) STORED'
    )
);
PREPARE ddl_command FROM @ddl_statement;
EXECUTE ddl_command;
DEALLOCATE PREPARE ddl_command;

SET @ddl_statement = (
    SELECT IF(
        EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = DATABASE()
               AND table_name = 'group_invite_links'
               AND column_name = 'active_group_id'
               AND extra LIKE '%STORED GENERATED%'
        )
      , 'SELECT 1'
      , 'ALTER TABLE group_invite_links MODIFY COLUMN active_group_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = ''ACTIVE'' THEN group_id ELSE NULL END) STORED'
    )
);
PREPARE ddl_command FROM @ddl_statement;
EXECUTE ddl_command;
DEALLOCATE PREPARE ddl_command;

SET @ddl_statement = (
    SELECT IF(
        EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = DATABASE()
               AND table_name = 'refund_requests'
               AND column_name = 'active_purchase_id'
               AND extra LIKE '%STORED GENERATED%'
        )
      , 'SELECT 1'
      , 'ALTER TABLE refund_requests MODIFY COLUMN active_purchase_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN (''REQUESTED'', ''CANCEL_PENDING'', ''CANCEL_UNKNOWN'') THEN purchase_id ELSE NULL END) STORED'
    )
);
PREPARE ddl_command FROM @ddl_statement;
EXECUTE ddl_command;
DEALLOCATE PREPARE ddl_command;

SET @ddl_statement = (
    SELECT IF(
        EXISTS (
            SELECT 1
              FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND table_name = 'group_memberships'
               AND index_name = 'uq_group_memberships_active_user'
        )
      , 'SELECT 1'
      , 'ALTER TABLE group_memberships ADD CONSTRAINT uq_group_memberships_active_user UNIQUE (active_user_id)'
    )
);
PREPARE ddl_command FROM @ddl_statement;
EXECUTE ddl_command;
DEALLOCATE PREPARE ddl_command;

SET @ddl_statement = (
    SELECT IF(
        EXISTS (
            SELECT 1
              FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND table_name = 'group_memberships'
               AND index_name = 'uq_group_memberships_primary_group'
        )
      , 'SELECT 1'
      , 'ALTER TABLE group_memberships ADD CONSTRAINT uq_group_memberships_primary_group UNIQUE (primary_group_id)'
    )
);
PREPARE ddl_command FROM @ddl_statement;
EXECUTE ddl_command;
DEALLOCATE PREPARE ddl_command;

SET @ddl_statement = (
    SELECT IF(
        EXISTS (
            SELECT 1
              FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND table_name = 'group_invite_links'
               AND index_name = 'uq_group_invite_links_active_group'
        )
      , 'SELECT 1'
      , 'ALTER TABLE group_invite_links ADD CONSTRAINT uq_group_invite_links_active_group UNIQUE (active_group_id)'
    )
);
PREPARE ddl_command FROM @ddl_statement;
EXECUTE ddl_command;
DEALLOCATE PREPARE ddl_command;

SET @ddl_statement = (
    SELECT IF(
        EXISTS (
            SELECT 1
              FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND table_name = 'refund_requests'
               AND index_name = 'uq_refund_requests_active_purchase'
        )
      , 'SELECT 1'
      , 'ALTER TABLE refund_requests ADD CONSTRAINT uq_refund_requests_active_purchase UNIQUE (active_purchase_id)'
    )
);
PREPARE ddl_command FROM @ddl_statement;
EXECUTE ddl_command;
DEALLOCATE PREPARE ddl_command;

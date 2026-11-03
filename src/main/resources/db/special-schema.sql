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

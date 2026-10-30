-- Spring Session JDBC's PostgreSQL layout; initialized only by Flyway.
CREATE TABLE stockrescue.spring_session (
    primary_id CHAR(36) PRIMARY KEY,
    session_id CHAR(36) NOT NULL UNIQUE,
    creation_time BIGINT NOT NULL CHECK (creation_time >= 0),
    last_access_time BIGINT NOT NULL CHECK (last_access_time >= creation_time),
    max_inactive_interval INTEGER NOT NULL CHECK (max_inactive_interval > 0),
    expiry_time BIGINT NOT NULL CHECK (expiry_time >= creation_time),
    principal_name VARCHAR(100)
);
CREATE INDEX spring_session_expiry_idx ON stockrescue.spring_session(expiry_time);
CREATE INDEX spring_session_principal_idx ON stockrescue.spring_session(principal_name);

CREATE TABLE stockrescue.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL REFERENCES stockrescue.spring_session(primary_id) ON DELETE CASCADE,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);

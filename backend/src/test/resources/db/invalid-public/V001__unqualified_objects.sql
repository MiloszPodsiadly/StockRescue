CREATE TABLE migration_leaked_table (id INTEGER);
CREATE VIEW migration_leaked_view AS SELECT id FROM migration_leaked_table;
CREATE SEQUENCE migration_leaked_sequence;
CREATE MATERIALIZED VIEW migration_leaked_materialized_view AS
    SELECT id FROM migration_leaked_table;
CREATE FUNCTION migration_leaked_function() RETURNS INTEGER
    LANGUAGE SQL IMMUTABLE AS 'SELECT 1';
CREATE TYPE migration_leaked_state AS ENUM ('pending', 'complete');

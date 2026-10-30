-- V9: the runtime role, so the running application never holds the right to change the schema.
--
-- Migrations are a deployment step (BRANCHBOOK_MIGRATE_ONLY=true), run by the database's owner. The application's
-- tasks then connect as the role created here, ${appRole} (Flyway placeholder appRole, from BRANCHBOOK_DB_APP_ROLE,
-- branchbook_app by default), which may read and write rows and nothing more: no CREATE, ALTER, DROP or TRUNCATE,
-- because it owns no table and is granted no CREATE on the schema, and no access at all to Flyway's history table, so
-- a compromised task can neither change the schema nor rewrite the record of which migrations ran. RuntimeRoleIT
-- proves both.
--
-- The role logs in but is given no password. In AWS it authenticates with IAM database authentication, so no database
-- password for the application exists anywhere; rds_iam, the role RDS uses for that, is granted only where it exists,
-- because plain PostgreSQL (docker compose, the tests) has no such role. Locally and in CI the application keeps
-- connecting as the owner, with a password, and this role simply goes unused. Roles belong to the whole server, not to
-- one database, so the role is created only if it is not there yet.

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${appRole}') THEN
    CREATE ROLE ${appRole} LOGIN;
  END IF;
END
$$;

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${appRole};

-- Every table that exists now, and, through the default privileges, every table and sequence a later migration run by
-- the same owner creates. The schema has no sequences today (every key is a UUID or a natural code); the grant is for a
-- future one, so a later migration cannot forget it.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${flyway:defaultSchema} TO ${appRole};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ${flyway:defaultSchema} TO ${appRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${appRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
  GRANT USAGE, SELECT ON SEQUENCES TO ${appRole};

-- The grant above covered Flyway's history table too; it is the one table the application must not touch.
REVOKE ALL ON TABLE ${flyway:defaultSchema}.${flyway:table} FROM ${appRole};

DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'rds_iam') THEN
    GRANT rds_iam TO ${appRole};
  END IF;
END
$$;

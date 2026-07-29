\set ON_ERROR_STOP on

-- Run once with a separately controlled PostgreSQL bootstrap administrator
-- before starting the production-profile application on a database that has
-- not yet applied V21. The login itself and its password are provisioned by
-- the platform/secret manager; this script creates only NOLOGIN capability
-- roles and grants the migration capability to that existing login.
\if :{?migration_login}
\else
  \echo 'migration_login psql variable is required'
  \quit 2
\endif

DO $bootstrap$
DECLARE
    role_name TEXT;
    attributes RECORD;
BEGIN
    FOREACH role_name IN ARRAY ARRAY[
        'vms_migration_owner',
        'vms_app_runtime',
        'vms_reporting',
        'vms_job_worker',
        'vms_migration_processor',
        'vms_backup'
    ]
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
            EXECUTE format(
                'CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB '
                'NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS',
                role_name);
        ELSE
            SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
                   rolinherit, rolreplication, rolbypassrls
            INTO attributes
            FROM pg_roles
            WHERE rolname = role_name;
            IF attributes.rolcanlogin
               OR attributes.rolsuper
               OR attributes.rolcreatedb
               OR attributes.rolcreaterole
               OR NOT attributes.rolinherit
               OR attributes.rolreplication
               OR attributes.rolbypassrls THEN
                RAISE EXCEPTION
                    'Capability role % has incompatible attributes',
                    role_name;
            END IF;
        END IF;
    END LOOP;
END;
$bootstrap$;

GRANT vms_migration_owner TO :"migration_login";

-- Rotated migration logins deliberately run future Flyway migrations without
-- SET ROLE. Secure their object-creation defaults now, so newly-created
-- functions never regain PostgreSQL's PUBLIC EXECUTE default.
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
    'REVOKE ALL ON TABLES FROM PUBLIC',
    :'migration_login')
\gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
    'REVOKE ALL ON SEQUENCES FROM PUBLIC',
    :'migration_login')
\gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
    'REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC',
    :'migration_login')
\gexec

SELECT pg_has_role(
    :'migration_login', 'vms_migration_owner', 'MEMBER'
) AS role_granted
\gset
\if :role_granted
\else
  \echo 'migration login was not granted vms_migration_owner'
  \quit 3
\endif

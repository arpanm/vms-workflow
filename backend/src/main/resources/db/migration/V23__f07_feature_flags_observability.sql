-- F07 server-authoritative feature flags. Definitions, scoped versions,
-- dependencies and transition evidence are append-only. No flag state grants a
-- permission: application services must still enforce their normal RBAC gate.

INSERT INTO permissions(id, code, description) VALUES
    ('10000000-0000-0000-0000-000000000090',
     'feature.flag.read',
     'Evaluate feature flags within an active organization or engagement scope'),
    ('10000000-0000-0000-0000-000000000091',
     'feature.flag.manage',
     'Create scoped immutable feature-flag versions');

INSERT INTO roles(id, code, name, description) VALUES
    ('11000000-0000-0000-0000-000000000090',
     'PLATFORM_ADMIN',
     'Platform administrator',
     'System-scoped feature flag governance; assignments are separately controlled');

INSERT INTO role_permissions(role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE (
    role.code = 'PLATFORM_ADMIN'
    AND permission.code IN ('feature.flag.read', 'feature.flag.manage')
) OR (
    role.code IN ('ORG_ADMIN', 'ENGAGEMENT_ADMIN')
    AND permission.code IN ('feature.flag.read', 'feature.flag.manage')
)
ON CONFLICT DO NOTHING;

CREATE TABLE f07_platform_role_assignments (
    id UUID PRIMARY KEY,
    user_profile_id UUID NOT NULL REFERENCES user_profiles(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    scope_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM'
        CHECK (scope_type = 'SYSTEM'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    valid_from DATE NOT NULL,
    valid_to DATE,
    assigned_by_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE (user_profile_id, role_id, valid_from)
);
CREATE INDEX idx_f07_platform_authority
    ON f07_platform_role_assignments(user_profile_id, status, valid_from, valid_to);

CREATE TABLE f07_feature_flags (
    id UUID PRIMARY KEY,
    flag_key VARCHAR(100) NOT NULL UNIQUE
        CHECK (flag_key ~ '^[a-z][a-z0-9]*(\.[a-z0-9]+)*$'),
    owner VARCHAR(120) NOT NULL,
    default_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(500) NOT NULL,
    created_by_subject VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE f07_feature_flag_versions (
    id UUID PRIMARY KEY,
    flag_id UUID NOT NULL REFERENCES f07_feature_flags(id),
    version INTEGER NOT NULL CHECK (version > 0),
    scope_type VARCHAR(16) NOT NULL
        CHECK (scope_type IN ('SYSTEM', 'ORGANIZATION', 'ENGAGEMENT')),
    organization_id UUID REFERENCES organizations(id),
    engagement_id UUID REFERENCES engagements(id),
    enabled BOOLEAN NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ,
    reason VARCHAR(300) NOT NULL,
    supersedes_id UUID REFERENCES f07_feature_flag_versions(id),
    created_by_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (effective_until IS NULL OR effective_until > effective_from),
    CHECK (
        (scope_type = 'SYSTEM'
            AND organization_id IS NULL AND engagement_id IS NULL)
        OR (scope_type = 'ORGANIZATION'
            AND organization_id IS NOT NULL AND engagement_id IS NULL)
        OR (scope_type = 'ENGAGEMENT'
            AND organization_id IS NOT NULL AND engagement_id IS NOT NULL)
    ),
    UNIQUE (flag_id, version)
);
CREATE INDEX idx_f07_feature_flag_effective
    ON f07_feature_flag_versions(
        flag_id, scope_type, organization_id, engagement_id, version DESC);

CREATE TABLE f07_feature_flag_dependencies (
    version_id UUID NOT NULL REFERENCES f07_feature_flag_versions(id),
    required_flag_id UUID NOT NULL REFERENCES f07_feature_flags(id),
    PRIMARY KEY (version_id, required_flag_id)
);

CREATE TABLE f07_feature_flag_transitions (
    id UUID PRIMARY KEY,
    flag_id UUID NOT NULL REFERENCES f07_feature_flags(id),
    version_id UUID REFERENCES f07_feature_flag_versions(id),
    action VARCHAR(24) NOT NULL
        CHECK (action IN ('DEFINED', 'VERSION_CREATED')),
    actor_subject VARCHAR(255) NOT NULL,
    authority_snapshot JSONB NOT NULL,
    reason VARCHAR(300) NOT NULL,
    correlation_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE f07_feature_flag_idempotency (
    actor_subject VARCHAR(255) NOT NULL,
    operation VARCHAR(16) NOT NULL
        CHECK (operation IN ('DEFINE', 'VERSION')),
    scope_key VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    result_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (
        actor_subject, operation, scope_key, idempotency_key)
);

CREATE OR REPLACE FUNCTION f07_validate_feature_flag_version()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    expected_version INTEGER;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.flag_id::text, 0));
    SELECT COALESCE(MAX(version), 0) + 1
    INTO expected_version
    FROM public.f07_feature_flag_versions
    WHERE flag_id = NEW.flag_id;
    IF NEW.version <> expected_version THEN
        RAISE EXCEPTION 'Feature flag version must be the next version'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.supersedes_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM public.f07_feature_flag_versions prior
        WHERE prior.id = NEW.supersedes_id
          AND prior.flag_id = NEW.flag_id
          AND prior.version = NEW.version - 1
    ) THEN
        RAISE EXCEPTION 'Feature flag supersedes link is invalid'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.scope_type = 'ENGAGEMENT' AND NOT EXISTS (
        SELECT 1
        FROM public.engagements engagement
        WHERE engagement.id = NEW.engagement_id
          AND NEW.organization_id IN (
              engagement.client_organization_id,
              engagement.vendor_organization_id,
              engagement.procurement_organization_id)
    ) THEN
        RAISE EXCEPTION 'Feature flag engagement is outside organization scope'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f07_feature_flag_version_guard
BEFORE INSERT ON f07_feature_flag_versions
FOR EACH ROW EXECUTE FUNCTION f07_validate_feature_flag_version();

CREATE OR REPLACE FUNCTION f07_reject_feature_flag_dependency_cycle()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    target_flag UUID;
    cycle_found BOOLEAN;
BEGIN
    -- Direct SQL writers and application writers share one graph-mutation
    -- lock. Reciprocal edges in concurrent transactions cannot both pass an
    -- MVCC visibility check.
    PERFORM pg_advisory_xact_lock(
        hashtextextended('f07-feature-flag-dependency-graph', 0));

    SELECT flag_id INTO target_flag
    FROM public.f07_feature_flag_versions
    WHERE id = NEW.version_id;

    -- Dependencies form a conservative immutable DAG across all versions.
    -- This prevents a future version from masking an edge that is active now
    -- (or will become active again in an overlapping scope/window).
    WITH RECURSIVE reachable(flag_id) AS (
        SELECT NEW.required_flag_id
        UNION
        SELECT relation.required_flag_id
        FROM reachable reachable_flag
        JOIN public.f07_feature_flag_versions version
          ON version.flag_id = reachable_flag.flag_id
        JOIN public.f07_feature_flag_dependencies relation
          ON relation.version_id = version.id
    )
    SELECT EXISTS (
        SELECT 1 FROM reachable WHERE flag_id = target_flag
    ) INTO cycle_found;

    IF cycle_found THEN
        RAISE EXCEPTION 'Feature flag dependency graph must be acyclic'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER f07_feature_flag_dependency_cycle_guard
BEFORE INSERT ON f07_feature_flag_dependencies
FOR EACH ROW EXECUTE FUNCTION f07_reject_feature_flag_dependency_cycle();

CREATE OR REPLACE FUNCTION f07_reject_feature_flag_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'F07 feature flag records are immutable';
END;
$$;

CREATE TRIGGER f07_feature_flags_immutable
BEFORE UPDATE OR DELETE ON f07_feature_flags
FOR EACH ROW EXECUTE FUNCTION f07_reject_feature_flag_mutation();
CREATE TRIGGER f07_feature_flag_versions_immutable
BEFORE UPDATE OR DELETE ON f07_feature_flag_versions
FOR EACH ROW EXECUTE FUNCTION f07_reject_feature_flag_mutation();
CREATE TRIGGER f07_feature_flag_dependencies_immutable
BEFORE UPDATE OR DELETE ON f07_feature_flag_dependencies
FOR EACH ROW EXECUTE FUNCTION f07_reject_feature_flag_mutation();
CREATE TRIGGER f07_feature_flag_transitions_immutable
BEFORE UPDATE OR DELETE ON f07_feature_flag_transitions
FOR EACH ROW EXECUTE FUNCTION f07_reject_feature_flag_mutation();
CREATE TRIGGER f07_feature_flag_idempotency_immutable
BEFORE UPDATE OR DELETE ON f07_feature_flag_idempotency
FOR EACH ROW EXECUTE FUNCTION f07_reject_feature_flag_mutation();

GRANT SELECT ON
    f07_platform_role_assignments,
    f07_feature_flags,
    f07_feature_flag_versions,
    f07_feature_flag_dependencies,
    f07_feature_flag_transitions,
    f07_feature_flag_idempotency
TO vms_app_runtime;
GRANT INSERT ON
    f07_feature_flags,
    f07_feature_flag_versions,
    f07_feature_flag_dependencies,
    f07_feature_flag_transitions,
    f07_feature_flag_idempotency
TO vms_app_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO vms_backup;

REVOKE EXECUTE ON FUNCTION
    f07_validate_feature_flag_version(),
    f07_reject_feature_flag_dependency_cycle(),
    f07_reject_feature_flag_mutation()
FROM PUBLIC;

ALTER TABLE f07_platform_role_assignments OWNER TO vms_migration_owner;
ALTER TABLE f07_feature_flags OWNER TO vms_migration_owner;
ALTER TABLE f07_feature_flag_versions OWNER TO vms_migration_owner;
ALTER TABLE f07_feature_flag_dependencies OWNER TO vms_migration_owner;
ALTER TABLE f07_feature_flag_transitions OWNER TO vms_migration_owner;
ALTER TABLE f07_feature_flag_idempotency OWNER TO vms_migration_owner;
ALTER FUNCTION f07_validate_feature_flag_version()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f07_reject_feature_flag_dependency_cycle()
    OWNER TO vms_migration_owner;
ALTER FUNCTION f07_reject_feature_flag_mutation()
    OWNER TO vms_migration_owner;

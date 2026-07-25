CREATE OR REPLACE FUNCTION enforce_role_assignment_scope_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.scope_type = 'ORGANIZATION' THEN
        IF NEW.scope_id <> NEW.organization_id THEN
            RAISE EXCEPTION 'Organization-scoped role assignment must target its organization'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.scope_type = 'ENGAGEMENT' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM engagements e
            WHERE e.id = NEW.scope_id
              AND (NEW.organization_id = e.client_organization_id
                OR NEW.organization_id = e.vendor_organization_id
                OR NEW.organization_id = e.procurement_organization_id)
        ) THEN
            RAISE EXCEPTION 'Engagement-scoped role assignment must target a participating organization engagement'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.scope_type = 'PROJECT' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM projects p
            JOIN engagements e ON e.id = p.engagement_id
            WHERE p.id = NEW.scope_id
              AND (NEW.organization_id = e.client_organization_id
                OR NEW.organization_id = e.vendor_organization_id
                OR NEW.organization_id = e.procurement_organization_id)
        ) THEN
            RAISE EXCEPTION 'Project-scoped role assignment must target a participating organization project'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported role assignment scope type: %', NEW.scope_type
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM role_assignments ra
        WHERE (ra.scope_type = 'ORGANIZATION' AND ra.scope_id <> ra.organization_id)
           OR (ra.scope_type = 'ENGAGEMENT' AND NOT EXISTS (
                SELECT 1
                FROM engagements e
                WHERE e.id = ra.scope_id
                  AND (ra.organization_id = e.client_organization_id
                    OR ra.organization_id = e.vendor_organization_id
                    OR ra.organization_id = e.procurement_organization_id)
           ))
           OR (ra.scope_type = 'PROJECT' AND NOT EXISTS (
                SELECT 1
                FROM projects p
                JOIN engagements e ON e.id = p.engagement_id
                WHERE p.id = ra.scope_id
                  AND (ra.organization_id = e.client_organization_id
                    OR ra.organization_id = e.vendor_organization_id
                    OR ra.organization_id = e.procurement_organization_id)
           ))
    ) THEN
        RAISE EXCEPTION 'Existing role assignment violates scope integrity'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE TRIGGER role_assignments_scope_integrity
BEFORE INSERT OR UPDATE OF organization_id, scope_type, scope_id
ON role_assignments
FOR EACH ROW
EXECUTE FUNCTION enforce_role_assignment_scope_integrity();

-- Policy-assignment commands are immutable evidence. Attach the organization
-- once, backfill it from the employee, and make all three referenced policy
-- objects prove the same organization at the database boundary.
ALTER TABLE employee_policy_assignment_commands
    ADD COLUMN organization_id UUID;

ALTER TABLE employee_policy_assignment_commands
    DISABLE TRIGGER employee_policy_assignment_commands_immutable;

UPDATE employee_policy_assignment_commands command
SET organization_id = employee.organization_id
FROM employees employee
WHERE employee.id = command.employee_id;

ALTER TABLE employee_policy_assignment_commands
    ENABLE TRIGGER employee_policy_assignment_commands_immutable;

ALTER TABLE employee_policy_assignment_commands
    ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE working_calendar_versions
    ADD CONSTRAINT uq_calendar_version_id_organization
        UNIQUE (id, organization_id);

ALTER TABLE leave_types
    ADD CONSTRAINT uq_leave_type_id_organization
        UNIQUE (id, organization_id);

ALTER TABLE employee_policy_assignment_commands
    DROP CONSTRAINT employee_policy_assignment_commands_employee_id_fkey,
    DROP CONSTRAINT
        employee_policy_assignment_commands_calendar_version_id_fkey,
    DROP CONSTRAINT employee_policy_assignment_commands_leave_type_id_fkey,
    ADD CONSTRAINT fk_policy_command_employee_organization
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employees(id, organization_id),
    ADD CONSTRAINT fk_policy_command_calendar_organization
        FOREIGN KEY (calendar_version_id, organization_id)
        REFERENCES working_calendar_versions(id, organization_id),
    ADD CONSTRAINT fk_policy_command_leave_type_organization
        FOREIGN KEY (leave_type_id, organization_id)
        REFERENCES leave_types(id, organization_id);

CREATE FUNCTION enforce_policy_command_organization()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    employee_organization UUID;
    calendar_organization UUID;
    leave_type_organization UUID;
BEGIN
    SELECT organization_id
      INTO employee_organization
    FROM public.employees
    WHERE id = NEW.employee_id;

    SELECT organization_id
      INTO calendar_organization
    FROM public.working_calendar_versions
    WHERE id = NEW.calendar_version_id;

    SELECT organization_id
      INTO leave_type_organization
    FROM public.leave_types
    WHERE id = NEW.leave_type_id;

    IF employee_organization IS NULL
       OR calendar_organization IS NULL
       OR leave_type_organization IS NULL
       OR employee_organization <> calendar_organization
       OR employee_organization <> leave_type_organization THEN
        RAISE EXCEPTION
            'Policy command employee, calendar and leave type organization mismatch'
            USING ERRCODE = '23514';
    END IF;

    NEW.organization_id := employee_organization;
    RETURN NEW;
END;
$$;

CREATE TRIGGER employee_policy_command_organization_gate
BEFORE INSERT ON employee_policy_assignment_commands
FOR EACH ROW EXECUTE FUNCTION enforce_policy_command_organization();

ALTER FUNCTION enforce_policy_command_organization()
    OWNER TO vms_migration_owner;
REVOKE ALL ON FUNCTION enforce_policy_command_organization() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enforce_policy_command_organization()
    TO vms_app_runtime;

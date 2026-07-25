-- Proposed schema outline for Cursor/Claude implementation.
-- NOT a production-ready migration. Generate additive, reviewed migrations and RLS tests.
-- Never add salary, CTC, markup, rate-card or payroll computation columns.

create extension if not exists pgcrypto;

-- 1. Tenant, identity and authorization
create table if not exists public.organizations (
  id uuid primary key default gen_random_uuid(),
  organization_type text not null check (organization_type in ('VENDOR','CLIENT','PROCUREMENT','OTHER')),
  legal_name text not null,
  display_name text not null,
  status text not null default 'ACTIVE',
  created_at timestamptz not null default now()
);

create table if not exists public.user_profiles (
  user_id uuid primary key references auth.users(id),
  display_name text not null,
  primary_email text not null,
  status text not null default 'ACTIVE',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.organization_memberships (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  user_id uuid not null references auth.users(id),
  role_code text not null,
  effective_from date not null,
  effective_to date,
  unique (organization_id, user_id, role_code, effective_from)
);

-- 2. Engagement and project masters
create table if not exists public.engagements_v2 (
  id uuid primary key default gen_random_uuid(),
  vendor_organization_id uuid not null references public.organizations(id),
  client_organization_id uuid not null references public.organizations(id),
  code text not null,
  name text not null,
  engagement_model text not null,
  start_date date not null,
  end_date date,
  status text not null default 'ACTIVE',
  unique (vendor_organization_id, client_organization_id, code)
);

create table if not exists public.projects (
  id uuid primary key default gen_random_uuid(),
  engagement_id uuid not null references public.engagements_v2(id),
  code text not null,
  name text not null,
  start_date date not null,
  end_date date,
  status text not null default 'ACTIVE',
  unique (engagement_id, code)
);

create table if not exists public.engagement_months (
  id uuid primary key default gen_random_uuid(),
  engagement_id uuid not null references public.engagements_v2(id),
  billing_month date not null check (date_trunc('month', billing_month)::date = billing_month),
  state text not null default 'DRAFT',
  version integer not null default 1,
  reopened_from_id uuid references public.engagement_months(id),
  locked_at timestamptz,
  created_at timestamptz not null default now(),
  unique (engagement_id, billing_month, version)
);

-- 3. Employee, allocation, calendar and leave
create table if not exists public.employees (
  id uuid primary key default gen_random_uuid(),
  employer_organization_id uuid not null references public.organizations(id),
  employee_code text not null,
  work_email text not null,
  display_name text not null,
  employment_start_date date not null,
  employment_end_date date,
  status text not null default 'ACTIVE',
  greythr_employee_ref text,
  unique (employer_organization_id, employee_code),
  unique (employer_organization_id, work_email)
);

create table if not exists public.employee_allocations (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  engagement_id uuid not null references public.engagements_v2(id),
  project_id uuid references public.projects(id),
  allocation_percent numeric(5,2) not null check (allocation_percent > 0 and allocation_percent <= 100),
  effective_from date not null,
  effective_to date,
  approved_by uuid references auth.users(id),
  approved_at timestamptz
);

create table if not exists public.working_calendars (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  code text not null,
  name text not null,
  timezone text not null default 'Asia/Kolkata',
  effective_from date not null,
  effective_to date,
  policy_json jsonb not null,
  unique (organization_id, code, effective_from)
);

create table if not exists public.holidays (
  id uuid primary key default gen_random_uuid(),
  calendar_id uuid not null references public.working_calendars(id),
  holiday_date date not null,
  name text not null,
  is_optional boolean not null default false,
  unique (calendar_id, holiday_date)
);

create table if not exists public.employee_calendar_assignments (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  calendar_id uuid not null references public.working_calendars(id),
  effective_from date not null,
  effective_to date
);

create table if not exists public.employee_date_overrides (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  work_date date not null,
  expectation text not null,
  reason text not null,
  approved_by uuid references auth.users(id),
  unique (employee_id, work_date)
);

create table if not exists public.leave_types (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  code text not null,
  name text not null,
  paid boolean not null,
  allow_negative_balance boolean not null default false,
  unique (organization_id, code)
);

create table if not exists public.leave_ledger (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  leave_type_id uuid not null references public.leave_types(id),
  transaction_date date not null,
  quantity_days numeric(7,3) not null,
  transaction_type text not null,
  source_ref text not null,
  idempotency_key text not null unique,
  created_at timestamptz not null default now()
);

create table if not exists public.leave_requests (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  leave_type_id uuid not null references public.leave_types(id),
  start_date date not null,
  end_date date not null,
  day_portion text not null default 'FULL',
  requested_days numeric(7,3) not null,
  status text not null default 'DRAFT',
  reason text not null,
  decided_by uuid references auth.users(id),
  decided_at timestamptz,
  check (end_date >= start_date)
);

-- 4. Attendance raw facts, computed days and regularization
create table if not exists public.attendance_punches (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  punch_time timestamptz not null,
  punch_type text not null check (punch_type in ('IN','OUT')),
  source_system text not null,
  external_ref text,
  idempotency_key text not null unique,
  received_at timestamptz not null default now()
);

create table if not exists public.attendance_sessions (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  work_date date not null,
  check_in_at timestamptz not null,
  check_out_at timestamptz,
  net_minutes integer,
  source_system text not null,
  status text not null
);

create table if not exists public.attendance_days (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  work_date date not null,
  expectation text not null,
  net_work_minutes integer not null default 0,
  attendance_result text not null,
  source_system text not null,
  policy_version text not null,
  computation_version integer not null default 1,
  exception_code text,
  locked_snapshot_id uuid,
  unique (employee_id, work_date, computation_version)
);

create table if not exists public.attendance_regularizations (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id),
  work_date date not null,
  requested_in_at timestamptz,
  requested_out_at timestamptz,
  justification text not null,
  status text not null default 'PENDING',
  decided_by uuid references auth.users(id),
  decided_at timestamptz
);

-- 5. Plans, deliverables and Linear evidence
create table if not exists public.monthly_plans (
  id uuid primary key default gen_random_uuid(),
  engagement_month_id uuid not null references public.engagement_months(id),
  version integer not null default 1,
  status text not null default 'DRAFT',
  submitted_at timestamptz,
  approved_at timestamptz,
  frozen_at timestamptz,
  supersedes_id uuid references public.monthly_plans(id),
  unique (engagement_month_id, version)
);

create table if not exists public.deliverables (
  id uuid primary key default gen_random_uuid(),
  monthly_plan_id uuid not null references public.monthly_plans(id),
  project_id uuid not null references public.projects(id),
  code text not null,
  title text not null,
  description text not null,
  business_outcome text not null,
  acceptance_criteria jsonb not null,
  target_date date not null,
  priority integer not null,
  product_owner_user_id uuid not null references auth.users(id),
  status text not null default 'PLANNED',
  unique (monthly_plan_id, code)
);

create table if not exists public.deliverable_employee_allocations (
  id uuid primary key default gen_random_uuid(),
  deliverable_id uuid not null references public.deliverables(id),
  employee_id uuid not null references public.employees(id),
  effective_from date not null,
  effective_to date,
  allocation_note text
);

create table if not exists public.integration_connections (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  provider text not null,
  status text not null,
  effective_from date not null,
  effective_to date,
  secret_reference text not null,
  configuration jsonb not null,
  capability_certification jsonb not null default '{}'::jsonb
);

create table if not exists public.deliverable_linear_links (
  id uuid primary key default gen_random_uuid(),
  deliverable_id uuid not null references public.deliverables(id),
  connection_id uuid not null references public.integration_connections(id),
  linear_issue_id text not null,
  identifier text not null,
  url text not null,
  linked_at timestamptz not null default now(),
  unique (deliverable_id, linear_issue_id)
);

create table if not exists public.linear_issue_current (
  id uuid primary key default gen_random_uuid(),
  connection_id uuid not null references public.integration_connections(id),
  linear_issue_id text not null,
  identifier text not null,
  provider_state text not null,
  normalized_state text not null,
  source_updated_at timestamptz not null,
  synchronized_at timestamptz not null,
  raw_payload_sha256 text not null,
  unique (connection_id, linear_issue_id)
);

create table if not exists public.linear_issue_snapshots (
  id uuid primary key default gen_random_uuid(),
  deliverable_linear_link_id uuid not null references public.deliverable_linear_links(id),
  snapshot_purpose text not null,
  captured_at timestamptz not null,
  snapshot_json jsonb not null,
  sha256 text not null,
  unique (deliverable_linear_link_id, snapshot_purpose, captured_at)
);

-- 6. Certification, confirmation, evidence and invoice
create table if not exists public.delivery_submissions (
  id uuid primary key default gen_random_uuid(),
  engagement_month_id uuid not null references public.engagement_months(id),
  version integer not null,
  submitted_by uuid not null references auth.users(id),
  submitted_at timestamptz not null,
  status text not null,
  unique (engagement_month_id, version)
);

create table if not exists public.deliverable_certifications (
  id uuid primary key default gen_random_uuid(),
  delivery_submission_id uuid not null references public.delivery_submissions(id),
  deliverable_id uuid not null references public.deliverables(id),
  certification_status text not null,
  certified_by uuid not null references auth.users(id),
  certified_at timestamptz not null,
  comments text,
  evidence_json jsonb not null default '[]'::jsonb,
  unique (delivery_submission_id, deliverable_id)
);

create table if not exists public.business_confirmations (
  id uuid primary key default gen_random_uuid(),
  engagement_month_id uuid not null references public.engagement_months(id),
  version integer not null,
  status text not null,
  requested_at timestamptz,
  confirmed_at timestamptz,
  confirmed_by uuid references auth.users(id),
  confirmation_channel text,
  provider_message_id text,
  provider_thread_id text,
  source_evidence_sha256 text,
  supersedes_id uuid references public.business_confirmations(id),
  unique (engagement_month_id, version)
);

create table if not exists public.evidence_snapshots (
  id uuid primary key default gen_random_uuid(),
  engagement_month_id uuid not null references public.engagement_months(id),
  snapshot_type text not null,
  version integer not null,
  captured_at timestamptz not null,
  captured_by uuid references auth.users(id),
  payload_json jsonb not null,
  sha256 text not null,
  supersedes_id uuid references public.evidence_snapshots(id),
  unique (engagement_month_id, snapshot_type, version)
);

create table if not exists public.evidence_packages (
  id uuid primary key default gen_random_uuid(),
  engagement_month_id uuid not null references public.engagement_months(id),
  version integer not null,
  status text not null,
  manifest_json jsonb not null,
  manifest_sha256 text not null,
  storage_prefix text not null,
  generated_at timestamptz not null,
  generated_by uuid not null references auth.users(id),
  supersedes_id uuid references public.evidence_packages(id),
  unique (engagement_month_id, version)
);

create table if not exists public.invoices_v2 (
  id uuid primary key default gen_random_uuid(),
  engagement_month_id uuid not null references public.engagement_months(id),
  invoice_number text not null,
  invoice_date date not null,
  invoice_amount numeric(18,2), -- metadata only; no derivation from salary/rates
  currency char(3) not null default 'INR',
  storage_object_id uuid not null,
  status text not null default 'DRAFT',
  evidence_package_id uuid references public.evidence_packages(id),
  submitted_at timestamptz,
  unique (engagement_month_id, invoice_number)
);

-- 7. Workflow, integrations, notifications, imports and immutable audit
create table if not exists public.approval_requests_v2 (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  engagement_month_id uuid references public.engagement_months(id),
  object_type text not null,
  object_id uuid not null,
  approval_type text not null,
  required_role text not null,
  assigned_user_id uuid references auth.users(id),
  status text not null default 'PENDING',
  due_at timestamptz,
  decided_at timestamptz,
  decision_comment text
);

create table if not exists public.notification_messages (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  event_type text not null,
  channel text not null,
  recipients jsonb not null,
  subject text,
  provider_message_id text,
  provider_thread_id text,
  status text not null,
  sent_at timestamptz,
  related_object_type text not null,
  related_object_id uuid not null
);

create table if not exists public.integration_events (
  id uuid primary key default gen_random_uuid(),
  connection_id uuid not null references public.integration_connections(id),
  provider_event_id text not null,
  event_type text not null,
  received_at timestamptz not null default now(),
  signature_verified boolean not null,
  processing_status text not null default 'RECEIVED',
  retry_count integer not null default 0,
  next_retry_at timestamptz,
  raw_payload_sha256 text not null,
  unique (connection_id, provider_event_id)
);

create table if not exists public.import_batches (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  template_code text not null,
  template_version text not null,
  file_sha256 text not null,
  state text not null default 'UPLOADED',
  dry_run_summary jsonb,
  committed_at timestamptz,
  rollback_at timestamptz,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now()
);

create table if not exists public.audit_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id),
  engagement_id uuid references public.engagements_v2(id),
  engagement_month_id uuid references public.engagement_months(id),
  event_type text not null,
  object_type text not null,
  object_id text not null,
  actor_type text not null,
  actor_id text not null,
  authority_role text,
  occurred_at timestamptz not null,
  represented_at timestamptz,
  correlation_id text not null,
  old_value jsonb,
  new_value jsonb,
  reason text,
  source_system text not null,
  event_sha256 text not null
);

-- Next implementation steps:
-- 1. Replace text status columns with reviewed enums or lookup tables where appropriate.
-- 2. Add exclusion constraints for effective-date overlaps and allocation totals.
-- 3. Add updated_at/version triggers and optimistic concurrency checks.
-- 4. Add append-only protections for audit/snapshot/ledger tables.
-- 5. Add RLS to every tenant-scoped table before data migration.
-- 6. Add storage_object/document tables, malware-scan status and signed URL controls.
-- 7. Add indexes based on PRD 13 query patterns and EXPLAIN plans.
-- 8. Map legacy engagements/requirements/approvals/uat_items/invoices through additive migrations.

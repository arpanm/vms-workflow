
-- Enums
CREATE TYPE req_status AS ENUM ('draft','submitted','reviewed','prioritized','estimated','approved','planned','in_development','uat','signed_off','closed');
CREATE TYPE req_priority AS ENUM ('p1','p2','p3','p4');
CREATE TYPE engagement_category AS ENUM ('app_development','analytics','staff_aug','middleware','api_integration','support','innovation');
CREATE TYPE approval_status AS ENUM ('pending','approved','rejected','auto_approved','escalated');
CREATE TYPE uat_status AS ENUM ('not_started','in_progress','blocked','signed_off','deemed_accepted');
CREATE TYPE invoice_status AS ENUM ('draft','uploaded','tech_approved','finance_approved','payment_initiated','paid');

CREATE TABLE public.engagements (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  vendor text NOT NULL,
  category engagement_category NOT NULL,
  monthly_capacity_hours int NOT NULL DEFAULT 0,
  approver text NOT NULL,
  business_owner text NOT NULL,
  color text NOT NULL DEFAULT 'primary',
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.requirements (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  engagement_id uuid REFERENCES public.engagements(id) ON DELETE CASCADE,
  title text NOT NULL,
  description text NOT NULL DEFAULT '',
  module text NOT NULL DEFAULT '',
  priority req_priority NOT NULL DEFAULT 'p3',
  rank int NOT NULL DEFAULT 100,
  status req_status NOT NULL DEFAULT 'draft',
  story_points int NOT NULL DEFAULT 0,
  estimated_hours int NOT NULL DEFAULT 0,
  business_owner text NOT NULL DEFAULT '',
  acceptance_criteria text NOT NULL DEFAULT '',
  uat_cases text NOT NULL DEFAULT '',
  business_justification text NOT NULL DEFAULT '',
  carry_forward boolean NOT NULL DEFAULT false,
  target_month date NOT NULL DEFAULT date_trunc('month', now())::date,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.approvals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id uuid REFERENCES public.requirements(id) ON DELETE CASCADE,
  approver text NOT NULL,
  status approval_status NOT NULL DEFAULT 'pending',
  requested_at timestamptz NOT NULL DEFAULT now(),
  acted_at timestamptz,
  sla_hours int NOT NULL DEFAULT 48,
  notes text NOT NULL DEFAULT ''
);

CREATE TABLE public.uat_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id uuid REFERENCES public.requirements(id) ON DELETE CASCADE,
  status uat_status NOT NULL DEFAULT 'not_started',
  uat_owner text NOT NULL DEFAULT '',
  handover_date date,
  signoff_date date,
  defects_open int NOT NULL DEFAULT 0,
  notes text NOT NULL DEFAULT ''
);

CREATE TABLE public.invoices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  engagement_id uuid REFERENCES public.engagements(id) ON DELETE CASCADE,
  invoice_number text NOT NULL,
  amount numeric(12,2) NOT NULL DEFAULT 0,
  currency text NOT NULL DEFAULT 'INR',
  status invoice_status NOT NULL DEFAULT 'draft',
  uploaded_at timestamptz NOT NULL DEFAULT now(),
  tech_approved_at timestamptz,
  finance_approved_at timestamptz,
  paid_at timestamptz,
  period_month date NOT NULL DEFAULT date_trunc('month', now())::date
);

-- Enable RLS, allow anon full access (prototype)
ALTER TABLE public.engagements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.requirements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.uat_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invoices ENABLE ROW LEVEL SECURITY;

CREATE POLICY "anon all" ON public.engagements FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY "anon all" ON public.requirements FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY "anon all" ON public.approvals FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY "anon all" ON public.uat_items FOR ALL USING (true) WITH CHECK (true);
CREATE POLICY "anon all" ON public.invoices FOR ALL USING (true) WITH CHECK (true);

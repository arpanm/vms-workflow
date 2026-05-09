import { useQuery } from "@tanstack/react-query";
import { supabase } from "@/integrations/supabase/client";

export type Engagement = {
  id: string;
  name: string;
  vendor: string;
  category: string;
  monthly_capacity_hours: number;
  approver: string;
  business_owner: string;
  color: string;
};

export type Requirement = {
  id: string;
  engagement_id: string | null;
  title: string;
  description: string;
  module: string;
  priority: "p1" | "p2" | "p3" | "p4";
  rank: number;
  status:
    | "draft" | "submitted" | "reviewed" | "prioritized" | "estimated"
    | "approved" | "planned" | "in_development" | "uat" | "signed_off" | "closed";
  story_points: number;
  estimated_hours: number;
  business_owner: string;
  acceptance_criteria: string;
  uat_cases: string;
  business_justification: string;
  carry_forward: boolean;
  target_month: string;
  created_at: string;
};

export type Approval = {
  id: string;
  requirement_id: string;
  approver: string;
  status: "pending" | "approved" | "rejected" | "auto_approved" | "escalated";
  requested_at: string;
  acted_at: string | null;
  sla_hours: number;
  notes: string;
};

export type UatItem = {
  id: string;
  requirement_id: string;
  status: "not_started" | "in_progress" | "blocked" | "signed_off" | "deemed_accepted";
  uat_owner: string;
  handover_date: string | null;
  signoff_date: string | null;
  defects_open: number;
};

export type Invoice = {
  id: string;
  engagement_id: string;
  invoice_number: string;
  amount: number;
  currency: string;
  status: "draft" | "uploaded" | "tech_approved" | "finance_approved" | "payment_initiated" | "paid";
  uploaded_at: string;
  tech_approved_at: string | null;
  finance_approved_at: string | null;
  paid_at: string | null;
  period_month: string;
};

export const useEngagements = () =>
  useQuery({
    queryKey: ["engagements"],
    queryFn: async () => {
      const { data, error } = await supabase.from("engagements").select("*").order("name");
      if (error) throw error;
      return data as Engagement[];
    },
  });

export const useRequirements = () =>
  useQuery({
    queryKey: ["requirements"],
    queryFn: async () => {
      const { data, error } = await supabase
        .from("requirements")
        .select("*")
        .order("rank", { ascending: true });
      if (error) throw error;
      return data as Requirement[];
    },
  });

export const useApprovals = () =>
  useQuery({
    queryKey: ["approvals"],
    queryFn: async () => {
      const { data, error } = await supabase.from("approvals").select("*");
      if (error) throw error;
      return data as Approval[];
    },
  });

export const useUat = () =>
  useQuery({
    queryKey: ["uat_items"],
    queryFn: async () => {
      const { data, error } = await supabase.from("uat_items").select("*");
      if (error) throw error;
      return data as UatItem[];
    },
  });

export const useInvoices = () =>
  useQuery({
    queryKey: ["invoices"],
    queryFn: async () => {
      const { data, error } = await supabase.from("invoices").select("*").order("uploaded_at", { ascending: false });
      if (error) throw error;
      return data as Invoice[];
    },
  });

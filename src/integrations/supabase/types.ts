export type Json =
  | string
  | number
  | boolean
  | null
  | { [key: string]: Json | undefined }
  | Json[]

export type Database = {
  // Allows to automatically instantiate createClient with right options
  // instead of createClient<Database, { PostgrestVersion: 'XX' }>(URL, KEY)
  __InternalSupabase: {
    PostgrestVersion: "14.5"
  }
  public: {
    Tables: {
      approvals: {
        Row: {
          acted_at: string | null
          approver: string
          id: string
          notes: string
          requested_at: string
          requirement_id: string | null
          sla_hours: number
          status: Database["public"]["Enums"]["approval_status"]
        }
        Insert: {
          acted_at?: string | null
          approver: string
          id?: string
          notes?: string
          requested_at?: string
          requirement_id?: string | null
          sla_hours?: number
          status?: Database["public"]["Enums"]["approval_status"]
        }
        Update: {
          acted_at?: string | null
          approver?: string
          id?: string
          notes?: string
          requested_at?: string
          requirement_id?: string | null
          sla_hours?: number
          status?: Database["public"]["Enums"]["approval_status"]
        }
        Relationships: [
          {
            foreignKeyName: "approvals_requirement_id_fkey"
            columns: ["requirement_id"]
            isOneToOne: false
            referencedRelation: "requirements"
            referencedColumns: ["id"]
          },
        ]
      }
      engagements: {
        Row: {
          approver: string
          business_owner: string
          category: Database["public"]["Enums"]["engagement_category"]
          color: string
          created_at: string
          id: string
          monthly_capacity_hours: number
          name: string
          vendor: string
        }
        Insert: {
          approver: string
          business_owner: string
          category: Database["public"]["Enums"]["engagement_category"]
          color?: string
          created_at?: string
          id?: string
          monthly_capacity_hours?: number
          name: string
          vendor: string
        }
        Update: {
          approver?: string
          business_owner?: string
          category?: Database["public"]["Enums"]["engagement_category"]
          color?: string
          created_at?: string
          id?: string
          monthly_capacity_hours?: number
          name?: string
          vendor?: string
        }
        Relationships: []
      }
      invoices: {
        Row: {
          amount: number
          currency: string
          engagement_id: string | null
          finance_approved_at: string | null
          id: string
          invoice_number: string
          paid_at: string | null
          period_month: string
          status: Database["public"]["Enums"]["invoice_status"]
          tech_approved_at: string | null
          uploaded_at: string
        }
        Insert: {
          amount?: number
          currency?: string
          engagement_id?: string | null
          finance_approved_at?: string | null
          id?: string
          invoice_number: string
          paid_at?: string | null
          period_month?: string
          status?: Database["public"]["Enums"]["invoice_status"]
          tech_approved_at?: string | null
          uploaded_at?: string
        }
        Update: {
          amount?: number
          currency?: string
          engagement_id?: string | null
          finance_approved_at?: string | null
          id?: string
          invoice_number?: string
          paid_at?: string | null
          period_month?: string
          status?: Database["public"]["Enums"]["invoice_status"]
          tech_approved_at?: string | null
          uploaded_at?: string
        }
        Relationships: [
          {
            foreignKeyName: "invoices_engagement_id_fkey"
            columns: ["engagement_id"]
            isOneToOne: false
            referencedRelation: "engagements"
            referencedColumns: ["id"]
          },
        ]
      }
      requirements: {
        Row: {
          acceptance_criteria: string
          business_justification: string
          business_owner: string
          carry_forward: boolean
          created_at: string
          description: string
          engagement_id: string | null
          estimated_hours: number
          id: string
          module: string
          priority: Database["public"]["Enums"]["req_priority"]
          rank: number
          status: Database["public"]["Enums"]["req_status"]
          story_points: number
          target_month: string
          title: string
          uat_cases: string
          updated_at: string
        }
        Insert: {
          acceptance_criteria?: string
          business_justification?: string
          business_owner?: string
          carry_forward?: boolean
          created_at?: string
          description?: string
          engagement_id?: string | null
          estimated_hours?: number
          id?: string
          module?: string
          priority?: Database["public"]["Enums"]["req_priority"]
          rank?: number
          status?: Database["public"]["Enums"]["req_status"]
          story_points?: number
          target_month?: string
          title: string
          uat_cases?: string
          updated_at?: string
        }
        Update: {
          acceptance_criteria?: string
          business_justification?: string
          business_owner?: string
          carry_forward?: boolean
          created_at?: string
          description?: string
          engagement_id?: string | null
          estimated_hours?: number
          id?: string
          module?: string
          priority?: Database["public"]["Enums"]["req_priority"]
          rank?: number
          status?: Database["public"]["Enums"]["req_status"]
          story_points?: number
          target_month?: string
          title?: string
          uat_cases?: string
          updated_at?: string
        }
        Relationships: [
          {
            foreignKeyName: "requirements_engagement_id_fkey"
            columns: ["engagement_id"]
            isOneToOne: false
            referencedRelation: "engagements"
            referencedColumns: ["id"]
          },
        ]
      }
      uat_items: {
        Row: {
          defects_open: number
          handover_date: string | null
          id: string
          notes: string
          requirement_id: string | null
          signoff_date: string | null
          status: Database["public"]["Enums"]["uat_status"]
          uat_owner: string
        }
        Insert: {
          defects_open?: number
          handover_date?: string | null
          id?: string
          notes?: string
          requirement_id?: string | null
          signoff_date?: string | null
          status?: Database["public"]["Enums"]["uat_status"]
          uat_owner?: string
        }
        Update: {
          defects_open?: number
          handover_date?: string | null
          id?: string
          notes?: string
          requirement_id?: string | null
          signoff_date?: string | null
          status?: Database["public"]["Enums"]["uat_status"]
          uat_owner?: string
        }
        Relationships: [
          {
            foreignKeyName: "uat_items_requirement_id_fkey"
            columns: ["requirement_id"]
            isOneToOne: false
            referencedRelation: "requirements"
            referencedColumns: ["id"]
          },
        ]
      }
    }
    Views: {
      [_ in never]: never
    }
    Functions: {
      [_ in never]: never
    }
    Enums: {
      approval_status:
        | "pending"
        | "approved"
        | "rejected"
        | "auto_approved"
        | "escalated"
      engagement_category:
        | "app_development"
        | "analytics"
        | "staff_aug"
        | "middleware"
        | "api_integration"
        | "support"
        | "innovation"
      invoice_status:
        | "draft"
        | "uploaded"
        | "tech_approved"
        | "finance_approved"
        | "payment_initiated"
        | "paid"
      req_priority: "p1" | "p2" | "p3" | "p4"
      req_status:
        | "draft"
        | "submitted"
        | "reviewed"
        | "prioritized"
        | "estimated"
        | "approved"
        | "planned"
        | "in_development"
        | "uat"
        | "signed_off"
        | "closed"
      uat_status:
        | "not_started"
        | "in_progress"
        | "blocked"
        | "signed_off"
        | "deemed_accepted"
    }
    CompositeTypes: {
      [_ in never]: never
    }
  }
}

type DatabaseWithoutInternals = Omit<Database, "__InternalSupabase">

type DefaultSchema = DatabaseWithoutInternals[Extract<keyof Database, "public">]

export type Tables<
  DefaultSchemaTableNameOrOptions extends
    | keyof (DefaultSchema["Tables"] & DefaultSchema["Views"])
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
        DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
      DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])[TableName] extends {
      Row: infer R
    }
    ? R
    : never
  : DefaultSchemaTableNameOrOptions extends keyof (DefaultSchema["Tables"] &
        DefaultSchema["Views"])
    ? (DefaultSchema["Tables"] &
        DefaultSchema["Views"])[DefaultSchemaTableNameOrOptions] extends {
        Row: infer R
      }
      ? R
      : never
    : never

export type TablesInsert<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Insert: infer I
    }
    ? I
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
    ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
        Insert: infer I
      }
      ? I
      : never
    : never

export type TablesUpdate<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Update: infer U
    }
    ? U
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
    ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
        Update: infer U
      }
      ? U
      : never
    : never

export type Enums<
  DefaultSchemaEnumNameOrOptions extends
    | keyof DefaultSchema["Enums"]
    | { schema: keyof DatabaseWithoutInternals },
  EnumName extends DefaultSchemaEnumNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"]
    : never = never,
> = DefaultSchemaEnumNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"][EnumName]
  : DefaultSchemaEnumNameOrOptions extends keyof DefaultSchema["Enums"]
    ? DefaultSchema["Enums"][DefaultSchemaEnumNameOrOptions]
    : never

export type CompositeTypes<
  PublicCompositeTypeNameOrOptions extends
    | keyof DefaultSchema["CompositeTypes"]
    | { schema: keyof DatabaseWithoutInternals },
  CompositeTypeName extends PublicCompositeTypeNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"]
    : never = never,
> = PublicCompositeTypeNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"][CompositeTypeName]
  : PublicCompositeTypeNameOrOptions extends keyof DefaultSchema["CompositeTypes"]
    ? DefaultSchema["CompositeTypes"][PublicCompositeTypeNameOrOptions]
    : never

export const Constants = {
  public: {
    Enums: {
      approval_status: [
        "pending",
        "approved",
        "rejected",
        "auto_approved",
        "escalated",
      ],
      engagement_category: [
        "app_development",
        "analytics",
        "staff_aug",
        "middleware",
        "api_integration",
        "support",
        "innovation",
      ],
      invoice_status: [
        "draft",
        "uploaded",
        "tech_approved",
        "finance_approved",
        "payment_initiated",
        "paid",
      ],
      req_priority: ["p1", "p2", "p3", "p4"],
      req_status: [
        "draft",
        "submitted",
        "reviewed",
        "prioritized",
        "estimated",
        "approved",
        "planned",
        "in_development",
        "uat",
        "signed_off",
        "closed",
      ],
      uat_status: [
        "not_started",
        "in_progress",
        "blocked",
        "signed_off",
        "deemed_accepted",
      ],
    },
  },
} as const

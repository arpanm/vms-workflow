package com.vms.workflow.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class MigrationTemplateRegistry {
    public static final String VERSION = "1";
    public static final Set<String> SOURCE_TYPES = Set.of(
        "GREYTHR_EXPORT", "LINEAR_API", "LINEAR_EXPORT", "ORIGINAL_EMAIL",
        "SIGNED_DOCUMENT", "APPROVED_SPREADSHEET", "MANUAL_RECONSTRUCTION",
        "OTHER");
    public static final Set<String> CONFIDENCE =
        Set.of("HIGH", "MEDIUM", "LOW", "UNVERIFIED");

    private final Map<String, Template> templates;

    public MigrationTemplateRegistry() {
        Map<String, Template> values = new LinkedHashMap<>();
        add(values, "01_employees", "01_employees_v1.csv", 1,
            "organization_code|employee_number", "",
            "7249aef8486cdc4e6ef77535a58163a9213e26388ec4564452e1fefdac879210",
            "template_version,organization_code,employee_number,first_name,last_name,display_name,work_email,join_date,exit_date,employment_status,designation,skill_category,manager_employee_number,timezone,working_calendar_code,attendance_policy_code,leave_policy_code,attendance_source_mode,greythr_employee_ref,activation_status,source_system,source_reference,notes");
        add(values, "02_employee_allocations", "02_employee_allocations_v1.csv", 2,
            "organization_code|employee_number|engagement_code|project_code|valid_from",
            "01_employees",
            "7556ed679ce1e78e5deec3c836460e72f215a70af95799d34bafb13602d7a69f",
            "template_version,organization_code,employee_number,engagement_code,project_code,team_code,valid_from,valid_to,deployment_status,allocation_percent,role_on_project,primary_flag,approved_by_email,represented_approval_at,override_reason,source_system,source_reference,notes");
        add(values, "03_holidays", "03_holidays_v1.csv", 2,
            "holiday_calendar_code|calendar_version|holiday_date|location_code",
            "",
            "9704101ea018b493c158983681d31ed75f1e834baa3736e60fa3198b9e441ec1",
            "template_version,organization_code,engagement_code,project_code,holiday_calendar_code,calendar_version,holiday_date,holiday_name,holiday_type,day_fraction,expected_minutes,optional_flag,location_code,approved_by_email,represented_approval_at,source_system,source_reference,notes");
        add(values, "04_employee_date_overrides", "04_employee_date_overrides_v1.csv", 2,
            "organization_code|employee_number|override_date", "01_employees|03_holidays",
            "eaef1c9e869af56da4895203a91ce1b78ce2f3e4ae545914cb4e0761f819e235",
            "template_version,organization_code,employee_number,override_date,resulting_classification,expected_minutes,reason_code,reason,approved_by_email,represented_approval_at,source_system,source_reference,notes");
        add(values, "05_leave_balances", "05_leave_balances_v1.csv", 2,
            "organization_code|employee_number|leave_type_code|idempotency_reference",
            "01_employees",
            "e351fb33240d98b0272a407944370d47ddb06c4096fba178e20bc57273cf5235",
            "template_version,organization_code,employee_number,leave_type_code,entry_type,quantity_days,effective_date,reference_id,reason,approved_by_email,represented_approval_at,idempotency_reference,source_system,source_reference,notes");
        add(values, "06_leave_requests", "06_leave_requests_v1.csv", 3,
            "organization_code|leave_request_external_id|leave_date|session",
            "01_employees|05_leave_balances",
            "56136ccb4aea43335b7ed3c8813cdb8e98f4fd965de686ddc019461462bd2c8b",
            "template_version,organization_code,leave_request_external_id,employee_number,leave_date,session,leave_type_code,quantity_days,request_status,decision_status,requested_at,represented_decision_at,approver_email,reason,evidence_reference,paid_lwp_classification,source_system,source_reference,notes");
        add(values, "07a_attendance_punches", "07a_attendance_punches_v1.csv", 3,
            "organization_code|attendance_event_external_id",
            "01_employees|02_employee_allocations|03_holidays|04_employee_date_overrides|06_leave_requests",
            "4ce4a20eabadbb1c6df69d679b51edad7ecac5ee3dac27d0f6b1a4b8d9854005",
            "template_version,organization_code,attendance_event_external_id,employee_number,event_type,occurred_at,timezone,source_system,source_reference,device_reference,supersedes_event_external_id,justification,evidence_reference,notes");
        add(values, "07b_attendance_daily", "07b_attendance_daily_v1.csv", 3,
            "organization_code|employee_number|attendance_date",
            "01_employees|02_employee_allocations|03_holidays|04_employee_date_overrides|06_leave_requests",
            "2f27a2a93da47d77b5fd13e9ea4bac9755fe08fa4a80187aaab8fbfcf6466258",
            "template_version,organization_code,employee_number,attendance_date,timezone,calendar_classification,expected_minutes,first_in_at,last_out_at,net_worked_minutes,final_attendance_status,paid_leave_type_code,paid_leave_days,lwp_days,regularization_status,regularization_reference,source_finalized_at,source_updated_at,source_system,source_reference,exception_code,notes");
        add(values, "08_deliverables", "08_deliverables_v1.csv", 4,
            "engagement_code|billing_month|plan_external_id|plan_version|deliverable_code",
            "01_employees|02_employee_allocations",
            "010f42b0caaa4e42e071d10b96d9019e0880fdb0f85eb5e82bec67d8db4731ac",
            "template_version,organization_code,engagement_code,billing_month,plan_external_id,plan_version,plan_type,plan_status,represented_plan_approved_at,plan_approved_by_email,deliverable_code,project_code,title,description,business_objective,product_owner_email,vendor_owner_email,priority,target_completion_date,delivery_category,acceptance_criteria,evidence_expectations,dependencies,risks_and_assumptions,assigned_employee_numbers,baseline_revision_reason,source_system,source_reference,notes");
        add(values, "09_deliverable_linear_links", "09_deliverable_linear_links_v1.csv", 4,
            "engagement_code|billing_month|deliverable_code|linear_issue_identifier",
            "08_deliverables",
            "913bd6b6892f746153db66106dd9ffaad369d7efd1e7086e507b86e8c3ca53af",
            "template_version,engagement_code,billing_month,deliverable_code,linear_issue_url,linear_issue_identifier,linear_issue_uuid,relationship_type,historical_snapshot_at,historical_state_name,historical_state_type,historical_assignee_email,historical_completed_at,historical_canceled_at,historical_updated_at,snapshot_confidence,source_system,source_reference,notes");
        add(values, "10_delivery_certifications", "10_delivery_certifications_v1.csv", 5,
            "engagement_code|billing_month|deliverable_code|represented_certification_at",
            "08_deliverables|09_deliverable_linear_links",
            "6f059fab0a694f7f7c7964a31d926d51373176b84671684d03f0364a1107e605",
            "template_version,engagement_code,billing_month,deliverable_code,vendor_declared_outcome,vendor_completion_percent,vendor_completion_date,vendor_summary,vendor_evidence_references,variance_cause,proposed_carry_forward_month,client_certification_decision,client_certification_comment,product_owner_email,represented_certification_at,acceptance_criteria_result_summary,source_system,source_reference,confidence,notes");
        add(values, "11_business_confirmations", "11_business_confirmations_v1.csv", 6,
            "engagement_code|billing_month|confirmation_external_id",
            "10_delivery_certifications",
            "fc1a496639033e98d7074ae5077d476caf01572a33a9d45b6b78ec5cb76dbff9",
            "template_version,engagement_code,billing_month,confirmation_external_id,request_subject,request_message_id,request_sent_at,request_to,request_cc,confirmed_version_reference,decision,actor_email,represented_response_at,response_message_id,response_thread_reference,evidence_filename,evidence_sha256,capture_method,source_system,source_reference,confidence,reviewer_email,review_comment,notes");
        add(values, "12_invoices", "12_invoices_v1.csv", 7,
            "vendor_organization_code|engagement_code|invoice_number|billing_month",
            "11_business_confirmations",
            "9ab76ca9c4b8b3636c4ad5959c925c4a853e9a25520c7aeb3a8207236867338d",
            "template_version,vendor_organization_code,client_organization_code,engagement_code,billing_month,invoice_number,invoice_date,billing_start_date,billing_end_date,po_reference,work_order_reference,currency,taxable_value,tax_amount,total_amount,invoice_filename,invoice_sha256,represented_uploaded_at,represented_submitted_at,procurement_status,represented_procurement_at,payment_status,represented_payment_at,external_ap_reference,source_system,source_reference,notes");
        add(values, "13_approval_history", "13_approval_history_v1.csv", 8,
            "engagement_code|object_type|object_external_id|object_version|action|represented_at",
            "08_deliverables|10_delivery_certifications|11_business_confirmations|12_invoices",
            "40f7622870cee04fe97360710f417ad4317961ea569beddd4a5e01518045abd1",
            "template_version,engagement_code,billing_month,object_type,object_external_id,object_version,action,decision,actor_email,actor_organization_code,actor_role,represented_at,comment,evidence_reference,source_system,source_reference,confidence,notes");
        templates = Map.copyOf(values);
    }

    public List<Template> all() {
        return templates.values().stream()
            .sorted((left, right) -> Integer.compare(left.wave(), right.wave()))
            .toList();
    }

    public Template require(String code) {
        Template value = templates.get(code);
        if (value == null) {
            throw new IllegalArgumentException("Unknown migration template.");
        }
        return value;
    }

    public byte[] safeSample(String code) {
        Template template = require(code);
        return (String.join(",", template.headers()) + "\r\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static void add(
        Map<String, Template> target,
        String code,
        String filename,
        int wave,
        String naturalKeys,
        String dependencies,
        String sampleChecksum,
        String headers
    ) {
        List<String> headerList = Arrays.asList(headers.split(",", -1));
        target.put(code, new Template(
            code, filename, VERSION, wave, headerList,
            Arrays.asList(naturalKeys.split("\\|", -1)),
            dependencies.isBlank()
                ? List.of() : Arrays.asList(dependencies.split("\\|")),
            sampleChecksum, sha256(headers + "\r\n"),
            SOURCE_TYPES, CONFIDENCE));
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable.", exception);
        }
    }

    public record Template(
        String code,
        String filename,
        String version,
        int wave,
        List<String> headers,
        List<String> naturalKeys,
        List<String> dependencies,
        String referenceSampleSha256,
        String generatedSampleSha256,
        Set<String> allowedSourceTypes,
        Set<String> allowedConfidence
    ) {
    }
}

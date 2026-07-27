package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the effective, versioned F05 policy used by package, upload,
 * readiness and Procurement workflows.
 */
@Service
public class FinancePolicyService {
    private static final List<String> DEFAULT_RULES = List.of(
        "ENGAGEMENT_CONTRACT", "ROSTER_ALLOCATION", "ATTENDANCE",
        "APPROVED_PLAN", "LINEAR_SNAPSHOT", "DELIVERY_CERTIFICATION",
        "VERIFIED_CONFIRMATION", "INVOICE_DOCUMENT", "PACKAGE_MANIFEST");
    private static final Set<String> DEFAULT_MIME_TYPES = Set.of(
        "application/pdf", "image/png", "image/jpeg");
    private static final Set<String> DEFAULT_CLASSIFICATIONS = Set.of(
        "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final JdbcTemplate jdbc;
    private final FinanceCanonicalJson canonical;

    public FinancePolicyService(
        JdbcTemplate jdbc,
        FinanceCanonicalJson canonical
    ) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    public Policy active(UUID engagementId, String creatorSubject) {
        Policy value = find(engagementId);
        if (value != null) {
            return value;
        }
        UUID id = UUID.randomUUID();
        Map<String, Object> defaults = Map.of(
            "version", 1,
            "allowedMimeTypes", DEFAULT_MIME_TYPES.stream().sorted().toList(),
            "allowedClassifications",
                DEFAULT_CLASSIFICATIONS.stream().sorted().toList(),
            "maximumUploadBytes", 25L * 1024L * 1024L,
            "mandatoryRules", DEFAULT_RULES,
            "exceptionSecondApprovalRequired", true,
            "retentionClass", "FINANCE_EVIDENCE");
        jdbc.update("""
            INSERT INTO f05_policy_versions(
                id, engagement_id, version, policy, effective_from,
                created_by_subject
            ) VALUES (?, ?, 1, ?::jsonb, CURRENT_TIMESTAMP, ?)
            ON CONFLICT (engagement_id, version) DO NOTHING
            """, id, engagementId, canonical.write(defaults), creatorSubject);
        Policy created = find(engagementId);
        if (created == null) {
            throw new IllegalStateException(
                "Unable to resolve the effective F05 policy.");
        }
        return created;
    }

    private Policy find(UUID engagementId) {
        return jdbc.query("""
            SELECT id, version, policy::text
            FROM f05_policy_versions
            WHERE engagement_id = ?
              AND effective_from <= CURRENT_TIMESTAMP
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP)
            ORDER BY version DESC
            LIMIT 1
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> document = canonical.readMap(rs.getString(3));
                return new Policy(
                    rs.getObject(1, UUID.class),
                    rs.getInt(2),
                    strings(document.get("allowedMimeTypes"), DEFAULT_MIME_TYPES),
                    longValue(document.get("maximumUploadBytes"),
                        25L * 1024L * 1024L),
                    strings(document.get("allowedClassifications"),
                        DEFAULT_CLASSIFICATIONS),
                    stringValue(document.get("retentionClass"),
                        "FINANCE_EVIDENCE"),
                    list(document.get("mandatoryRules"), DEFAULT_RULES),
                    booleanValue(
                        document.get("exceptionSecondApprovalRequired"), true));
            }, engagementId);
    }

    private static Set<String> strings(Object value, Set<String> defaults) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return defaults;
        }
        Set<String> result = new LinkedHashSet<>();
        values.forEach(item -> result.add(String.valueOf(item)));
        return Set.copyOf(result);
    }

    private static List<String> list(Object value, List<String> defaults) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return defaults;
        }
        return values.stream().map(String::valueOf).toList();
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank()
            ? fallback : String.valueOf(value);
    }

    public record Policy(
        UUID id,
        int version,
        Set<String> allowedMimeTypes,
        long maximumUploadBytes,
        Set<String> allowedClassifications,
        String retentionClass,
        List<String> mandatoryRules,
        boolean exceptionSecondApprovalRequired
    ) {
        public String label() {
            return "f05-policy-v" + version;
        }
    }
}

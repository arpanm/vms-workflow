package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Typed compatibility boundary between the immutable F04 close handoff and F05.
 */
@Service
public class FinanceF04EvidenceResolver {
    public static final String CONTRACT_VERSION =
        "certification.confirmation.readiness.v1";
    public static final String HANDOFF_SCHEMA = "f04-f05-handoff-v1";
    private static final Set<String> REQUIRED_PILLARS = Set.of(
        "ROSTER_ALLOCATION", "ATTENDANCE", "PLAN_LINEAR",
        "CERTIFICATION", "CONFIRMATION_F05");

    private final JdbcTemplate jdbc;
    private final FinanceCanonicalJson canonical;

    public FinanceF04EvidenceResolver(
        JdbcTemplate jdbc,
        FinanceCanonicalJson canonical
    ) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    public HandoffEvidence resolve(UUID monthId) {
        HandoffRow handoff = jdbc.query("""
            SELECT handoff.id, handoff.readiness_run_id,
                   handoff.confirmation_request_id,
                   handoff.package_manifest::text, handoff.package_hash,
                   handoff.status, handoff.created_at
            FROM effective_f05_certification_handoffs handoff
            WHERE handoff.engagement_month_id = ?
              AND handoff.effective_status <> 'INVALIDATED'
            ORDER BY handoff.created_at DESC, handoff.id
            LIMIT 1
            """, rs -> rs.next() ? new HandoffRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), offset(rs.getTimestamp(7))) : null, monthId);
        if (handoff == null) {
            throw conflict("F05_HANDOFF_REQUIRED",
                "An effective F04 certification handoff is required.");
        }
        Map<String, Object> manifest = canonical.readMap(handoff.manifest());
        if (!HANDOFF_SCHEMA.equals(manifest.get("schema"))
            || !monthId.toString().equals(String.valueOf(
                manifest.get("engagementMonthId")))
            || !handoff.readinessRunId().toString().equals(String.valueOf(
                manifest.get("readinessRunId")))
            || !handoff.confirmationRequestId().toString().equals(
                String.valueOf(manifest.get("confirmationRequestId")))
            || !canonical.sha256(manifest).equals(handoff.hash())) {
            throw conflict("F04_HANDOFF_INCOMPATIBLE",
                "The F04 handoff contract is incomplete or incompatible.");
        }

        ReadinessRow readiness = jdbc.query("""
            SELECT input_manifest::text, input_hash, status,
                   ready_for_f05_handoff, evaluated_at
            FROM certification_readiness_runs
            WHERE id = ? AND engagement_month_id = ?
            """, rs -> rs.next() ? new ReadinessRow(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), offset(rs.getTimestamp(5))) : null,
            handoff.readinessRunId(), monthId);
        if (readiness == null || !readiness.readyForF05()
            || !"READY_FOR_F05".equals(readiness.status())) {
            throw conflict("F04_READINESS_INELIGIBLE",
                "The exact F04 readiness run is not eligible for F05.");
        }
        Map<String, Object> readinessManifest =
            canonical.readMap(readiness.manifest());
        if (!canonical.sha256(readinessManifest).equals(readiness.inputHash())) {
            throw conflict("F04_READINESS_HASH_MISMATCH",
                "The F04 readiness input hash failed integrity validation.");
        }

        List<PillarFact> facts = jdbc.query("""
            SELECT id, pillar, status, source_object_type, source_object_id,
                   source_version, freshness, blocker_code, severity,
                   owner_role, action_cta, details::text
            FROM certification_readiness_results
            WHERE run_id = ?
            ORDER BY pillar, id
            """, (rs, rowNum) -> new PillarFact(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11),
                canonical.readMap(rs.getString(12))), handoff.readinessRunId());
        if (!facts.stream().map(PillarFact::pillar).collect(
            java.util.stream.Collectors.toSet()).containsAll(REQUIRED_PILLARS)
            || facts.stream().anyMatch(fact -> !"READY".equals(fact.status()))) {
            throw conflict("F04_READINESS_INCOMPLETE",
                "Every required F04 evidence pillar must be present and ready.");
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(source("F04_HANDOFF", handoff.id(),
            handoff.hash(), handoff.status(), handoff.createdAt()));
        sources.add(source("F04_READINESS_RUN", handoff.readinessRunId(),
            readiness.inputHash(), readiness.status(), readiness.evaluatedAt()));
        for (PillarFact fact : facts) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("pillar", fact.pillar());
            value.put("resultId", fact.id());
            value.put("status", fact.status());
            value.put("sourceObjectType", fact.sourceType());
            value.put("sourceObjectId", fact.sourceId());
            value.put("sourceVersion", fact.sourceVersion());
            value.put("freshness", fact.freshness());
            value.put("details", fact.details());
            sources.add(value);
        }
        return new HandoffEvidence(
            handoff.id(), monthId, handoff.confirmationRequestId(),
            handoff.readinessRunId(), handoff.hash(), handoff.createdAt(),
            readiness.inputHash(), readiness.evaluatedAt(),
            immutableMap(manifest), immutableMap(readinessManifest),
            List.copyOf(facts), List.copyOf(sources));
    }

    public void rememberConsumption(
        HandoffEvidence evidence,
        String subject,
        UUID correlationId
    ) {
        jdbc.update("""
            INSERT INTO f05_handoff_consumptions(
                id, handoff_id, engagement_month_id, contract_version,
                source_hash, consumed_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (handoff_id) DO NOTHING
            """, UUID.randomUUID(), evidence.handoffId(), evidence.monthId(),
            CONTRACT_VERSION, evidence.handoffHash(), subject, correlationId);
    }

    private Map<String, Object> source(
        String type,
        UUID id,
        String hash,
        String state,
        OffsetDateTime recordedAt
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceObjectType", type);
        value.put("sourceObjectId", id);
        value.put("sourceHash", hash);
        value.put("state", state);
        value.put("recordedAt", recordedAt);
        return value;
    }

    private DomainConflictException conflict(String code, String message) {
        return new DomainConflictException(code, message);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private record HandoffRow(
        UUID id,
        UUID readinessRunId,
        UUID confirmationRequestId,
        String manifest,
        String hash,
        String status,
        OffsetDateTime createdAt
    ) {
    }

    private record ReadinessRow(
        String manifest,
        String inputHash,
        String status,
        boolean readyForF05,
        OffsetDateTime evaluatedAt
    ) {
    }

    public record PillarFact(
        UUID id,
        String pillar,
        String status,
        String sourceType,
        UUID sourceId,
        String sourceVersion,
        String freshness,
        String blockerCode,
        String severity,
        String owner,
        String action,
        Map<String, Object> details
    ) {
    }

    public record HandoffEvidence(
        UUID handoffId,
        UUID monthId,
        UUID confirmationRequestId,
        UUID readinessRunId,
        String handoffHash,
        OffsetDateTime handoffCreatedAt,
        String readinessHash,
        OffsetDateTime readinessEvaluatedAt,
        Map<String, Object> handoffManifest,
        Map<String, Object> readinessManifest,
        List<PillarFact> pillars,
        List<Map<String, Object>> sources
    ) {
        public PillarFact pillar(String code) {
            return pillars.stream()
                .filter(value -> code.equals(value.pillar()))
                .findFirst()
                .orElseThrow();
        }
    }
}

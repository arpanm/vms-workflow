package com.vms.workflow.application;

import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.infrastructure.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CertificationSecurityEventService {
    private static final Logger LOGGER =
        LoggerFactory.getLogger(CertificationSecurityEventService.class);

    private final JdbcTemplate jdbc;
    private final CanonicalEvidenceHasher hasher;

    public CertificationSecurityEventService(
        JdbcTemplate jdbc,
        CanonicalEvidenceHasher hasher
    ) {
        this.jdbc = jdbc;
        this.hasher = hasher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBestEffort(
        UUID monthId,
        String eventType,
        String actorSubject,
        String objectType,
        UUID objectId,
        String outcome,
        String reasonCode,
        Map<String, ?> safeFacts
    ) {
        try {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("reasonCode", safeCode(reasonCode));
            if (safeFacts != null) {
                safeFacts.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        facts.put(key, value);
                    }
                });
            }
            jdbc.update("""
                INSERT INTO certification_security_events
                    (id, engagement_month_id, event_type,
                     actor_subject_hash, object_type, object_id,
                     outcome, redacted_facts, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), monthId, safeCode(eventType),
                actorSubject == null || actorSubject.isBlank()
                    ? null : hasher.sha256(actorSubject),
                safeCode(objectType), objectId, safeCode(outcome),
                hasher.hash(SensitiveDataRedactor.structuredFacts(facts)).canonicalJson(),
                CorrelationIdFilter.currentOrNew());
        } catch (RuntimeException exception) {
            LOGGER.error(
                "F04 security event persistence failed for type={} reason={}",
                safeCode(eventType), safeCode(reasonCode));
        }
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNSPECIFIED";
        }
        String normalized = value.toUpperCase(java.util.Locale.ROOT)
            .replaceAll("[^A-Z0-9_.:-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 100));
    }
}

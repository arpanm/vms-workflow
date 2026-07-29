package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.FeatureFlagDtos.DefinitionInput;
import com.vms.workflow.api.FeatureFlagDtos.VersionInput;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.security.FeatureFlagAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FeatureFlagService {
    private final JdbcTemplate jdbc;
    private final FeatureFlagAuthorizationService authorization;
    private final FinanceCanonicalJson canonical;
    private final Clock clock;

    public FeatureFlagService(
        JdbcTemplate jdbc,
        FeatureFlagAuthorizationService authorization,
        FinanceCanonicalJson canonical,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.canonical = canonical;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> define(
        String subject,
        String idempotencyKey,
        DefinitionInput input
    ) {
        authorization.requireDefinitionManagement(subject);
        Idempotency replay = beginIdempotentMutation(
            subject, "DEFINE", input.key(), idempotencyKey, input);
        if (replay != null) {
            return definition(replay.resultId());
        }
        UUID id = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        Map<String, Object> authority = authority(
            "SYSTEM", null, null, "feature.flag.manage");
        jdbc.update("""
            INSERT INTO f07_feature_flags(
                id, flag_key, owner, default_enabled, description,
                created_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, input.key(), input.owner(), input.defaultEnabled(),
            input.description(), subject, correlationId);
        jdbc.update("""
            INSERT INTO f07_feature_flag_transitions(
                id, flag_id, version_id, action, actor_subject,
                authority_snapshot, reason, correlation_id
            ) VALUES (?, ?, NULL, 'DEFINED', ?, ?::jsonb, ?, ?)
            """, UUID.randomUUID(), id, subject, canonical.write(authority),
            input.reason(), correlationId);
        rememberIdempotentMutation(
            subject, "DEFINE", input.key(), idempotencyKey, input, id);
        return definition(id);
    }

    @Transactional
    public Map<String, Object> version(
        String subject,
        String key,
        String idempotencyKey,
        VersionInput input
    ) {
        String scope = input.scopeType();
        validateScope(scope, input.organizationId(), input.engagementId());
        authorization.requireVersionManagement(
            subject, scope, input.organizationId(), input.engagementId());
        if (input.effectiveUntil() != null
            && !input.effectiveUntil().isAfter(input.effectiveFrom())) {
            throw new IllegalArgumentException(
                "Effective-until must be after effective-from.");
        }
        Flag flag = flag(key);
        Idempotency replay = beginIdempotentMutation(
            subject, "VERSION", key, idempotencyKey, input);
        if (replay != null) {
            return versionView(key, replay.resultId());
        }
        jdbc.queryForObject("""
            SELECT pg_advisory_xact_lock(
                hashtextextended('f07-feature-flag-dependency-graph', 0))
            """, Object.class);
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            Object.class, flag.id().toString());
        Prior prior = jdbc.query("""
            SELECT id, version
            FROM f07_feature_flag_versions
            WHERE flag_id = ?
            ORDER BY version DESC
            LIMIT 1
            FOR UPDATE
            """, rs -> rs.next()
                ? new Prior(rs.getObject(1, UUID.class), rs.getInt(2))
                : null, flag.id());
        List<Flag> dependencies = input.dependencies().stream()
            .distinct()
            .map(this::flag)
            .toList();
        if (dependencies.stream().anyMatch(value -> value.id().equals(flag.id()))) {
            throw new DomainConflictException(
                "FEATURE_FLAG_SELF_DEPENDENCY",
                "A feature flag cannot depend on itself.");
        }
        if (createsDependencyCycle(flag.id(), dependencies)) {
            throw new DomainConflictException(
                "FEATURE_FLAG_DEPENDENCY_CYCLE",
                "The feature flag dependency graph must remain acyclic.");
        }
        int version = prior == null ? 1 : prior.version() + 1;
        UUID versionId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        Map<String, Object> authority = authority(
            scope, input.organizationId(), input.engagementId(),
            "feature.flag.manage");
        jdbc.update("""
            INSERT INTO f07_feature_flag_versions(
                id, flag_id, version, scope_type, organization_id,
                engagement_id, enabled, effective_from, effective_until,
                reason, supersedes_id, created_by_subject,
                authority_snapshot, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """, versionId, flag.id(), version, scope, input.organizationId(),
            input.engagementId(), input.enabled(),
            Timestamp.from(input.effectiveFrom().toInstant()),
            input.effectiveUntil() == null ? null
                : Timestamp.from(input.effectiveUntil().toInstant()),
            input.reason(), prior == null ? null : prior.id(), subject,
            canonical.write(authority), correlationId);
        dependencies.forEach(dependency -> jdbc.update("""
            INSERT INTO f07_feature_flag_dependencies(
                version_id, required_flag_id
            ) VALUES (?, ?)
            """, versionId, dependency.id()));
        jdbc.update("""
            INSERT INTO f07_feature_flag_transitions(
                id, flag_id, version_id, action, actor_subject,
                authority_snapshot, reason, correlation_id
            ) VALUES (?, ?, ?, 'VERSION_CREATED', ?, ?::jsonb, ?, ?)
            """, UUID.randomUUID(), flag.id(), versionId, subject,
            canonical.write(authority), input.reason(), correlationId);
        rememberIdempotentMutation(
            subject, "VERSION", key, idempotencyKey, input, versionId);
        return versionView(key, versionId);
    }

    private Idempotency beginIdempotentMutation(
        String subject,
        String operation,
        String scopeKey,
        String idempotencyKey,
        Object input
    ) {
        requireIdempotencyKey(idempotencyKey);
        jdbc.queryForObject("""
            SELECT pg_advisory_xact_lock(hashtextextended(?, 0))
            """, Object.class, String.join(
                ":", "f07-feature-flag-idempotency", subject, operation,
                scopeKey, idempotencyKey));
        Idempotency existing = jdbc.query("""
            SELECT request_hash, result_id
            FROM f07_feature_flag_idempotency
            WHERE actor_subject = ? AND operation = ?
              AND scope_key = ? AND idempotency_key = ?
            """, rs -> rs.next()
                ? new Idempotency(rs.getString(1),
                    rs.getObject(2, UUID.class))
                : null, subject, operation, scopeKey, idempotencyKey);
        if (existing != null
            && !existing.requestHash().equals(canonical.sha256(input))) {
            throw new DomainConflictException(
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with a different request.");
        }
        return existing;
    }

    private void rememberIdempotentMutation(
        String subject,
        String operation,
        String scopeKey,
        String idempotencyKey,
        Object input,
        UUID resultId
    ) {
        jdbc.update("""
            INSERT INTO f07_feature_flag_idempotency(
                actor_subject, operation, scope_key, idempotency_key,
                request_hash, result_id
            ) VALUES (?, ?, ?, ?, ?, ?)
            """, subject, operation, scopeKey, idempotencyKey,
            canonical.sha256(input), resultId);
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
            || idempotencyKey.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key is required and must not exceed 160 characters.");
        }
    }

    public Map<String, Object> evaluate(
        String subject,
        String key,
        UUID organizationId,
        UUID engagementId
    ) {
        authorization.requireEvaluation(
            subject, organizationId, engagementId);
        if (engagementId != null && !engagementBelongsTo(
            organizationId, engagementId)) {
            throw new IllegalArgumentException(
                "Engagement is outside the requested organization.");
        }
        Flag flag = flag(key);
        Instant evaluatedAt = clock.instant();
        Evaluation evaluation = evaluate(
            flag, organizationId, engagementId, evaluatedAt, new HashSet<>());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("enabled", evaluation.enabled());
        result.put("source", evaluation.source());
        result.put("version", evaluation.version());
        result.put("evaluatedAt", evaluatedAt);
        result.put("authorizationGranted", false);
        return result;
    }

    private Evaluation evaluate(
        Flag flag,
        UUID organizationId,
        UUID engagementId,
        Instant at,
        Set<UUID> visiting
    ) {
        if (!visiting.add(flag.id())) {
            return new Evaluation(false, "DEPENDENCY_CYCLE", null);
        }
        Effective effective = effective(
            flag.id(), organizationId, engagementId, at);
        boolean enabled = effective == null
            ? flag.defaultEnabled()
            : effective.enabled();
        String source = effective == null ? "DEFAULT" : effective.scopeType();
        Integer version = effective == null ? null : effective.version();
        if (!enabled) {
            visiting.remove(flag.id());
            return new Evaluation(false, source, version);
        }
        if (effective != null) {
            List<Flag> dependencies = jdbc.query("""
                SELECT dependency.id, dependency.flag_key,
                       dependency.default_enabled
                FROM f07_feature_flag_dependencies relation
                JOIN f07_feature_flags dependency
                  ON dependency.id = relation.required_flag_id
                WHERE relation.version_id = ?
                ORDER BY dependency.flag_key
                """, (rs, row) -> new Flag(
                    rs.getObject(1, UUID.class),
                    rs.getString(2), rs.getBoolean(3)), effective.id());
            for (Flag dependency : dependencies) {
                if (!evaluate(dependency, organizationId, engagementId, at,
                    visiting).enabled()) {
                    visiting.remove(flag.id());
                    return new Evaluation(
                        false, "DEPENDENCY_UNSATISFIED", version);
                }
            }
        }
        visiting.remove(flag.id());
        return new Evaluation(true, source, version);
    }

    private Effective effective(
        UUID flagId,
        UUID organizationId,
        UUID engagementId,
        Instant at
    ) {
        return jdbc.query("""
            SELECT id, version, scope_type, enabled
            FROM f07_feature_flag_versions
            WHERE flag_id = ?
              AND effective_from <= ?
              AND (effective_until IS NULL OR effective_until > ?)
              AND (
                  scope_type = 'SYSTEM'
                  OR (scope_type = 'ORGANIZATION' AND organization_id = ?)
                  OR (scope_type = 'ENGAGEMENT'
                      AND organization_id = ? AND engagement_id = ?)
              )
            ORDER BY CASE scope_type
                WHEN 'ENGAGEMENT' THEN 3
                WHEN 'ORGANIZATION' THEN 2
                ELSE 1 END DESC,
                version DESC
            LIMIT 1
            """, rs -> rs.next() ? new Effective(
                rs.getObject(1, UUID.class), rs.getInt(2),
                rs.getString(3), rs.getBoolean(4)) : null,
            flagId, Timestamp.from(at), Timestamp.from(at),
            organizationId, organizationId, engagementId);
    }

    private Map<String, Object> definition(UUID id) {
        return jdbc.queryForObject("""
            SELECT flag_key, owner, default_enabled, description, created_at
            FROM f07_feature_flags WHERE id = ?
            """, (rs, row) -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("flagId", id);
                result.put("key", rs.getString(1));
                result.put("owner", rs.getString(2));
                result.put("defaultEnabled", rs.getBoolean(3));
                result.put("description", rs.getString(4));
                result.put("createdAt", rs.getTimestamp(5).toInstant());
                return result;
            }, id);
    }

    private Map<String, Object> versionView(String key, UUID id) {
        return jdbc.queryForObject("""
            SELECT version, scope_type, organization_id, engagement_id,
                   enabled, effective_from, effective_until, reason
            FROM f07_feature_flag_versions WHERE id = ?
            """, (rs, row) -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("versionId", id);
                result.put("key", key);
                result.put("version", rs.getInt(1));
                result.put("scopeType", rs.getString(2));
                result.put("organizationId",
                    rs.getObject(3, UUID.class));
                result.put("engagementId", rs.getObject(4, UUID.class));
                result.put("enabled", rs.getBoolean(5));
                result.put("effectiveFrom",
                    rs.getTimestamp(6).toInstant());
                result.put("effectiveUntil", rs.getTimestamp(7) == null
                    ? null : rs.getTimestamp(7).toInstant());
                result.put("reason", rs.getString(8));
                return result;
            }, id);
    }

    private Flag flag(String key) {
        List<Flag> values = jdbc.query("""
            SELECT id, flag_key, default_enabled
            FROM f07_feature_flags WHERE flag_key = ?
            """, (rs, row) -> new Flag(
                rs.getObject(1, UUID.class),
                rs.getString(2), rs.getBoolean(3)), key);
        if (values.isEmpty()) {
            throw new EntityNotFoundException("Feature flag not found.");
        }
        return values.getFirst();
    }

    private boolean createsDependencyCycle(
        UUID targetFlagId,
        List<Flag> dependencies
    ) {
        if (dependencies.isEmpty()) {
            return false;
        }
        String roots = String.join(", ",
            java.util.Collections.nCopies(
                dependencies.size(), "(?::uuid)"));
        String sql = """
            WITH RECURSIVE reachable(flag_id) AS (
                VALUES %s
                UNION
                SELECT relation.required_flag_id
                FROM reachable reachable_flag
                JOIN f07_feature_flag_versions version
                  ON version.flag_id = reachable_flag.flag_id
                JOIN f07_feature_flag_dependencies relation
                  ON relation.version_id = version.id
            )
            SELECT EXISTS (
                SELECT 1 FROM reachable WHERE flag_id = ?
            )
            """.formatted(roots);
        Object[] arguments = new Object[dependencies.size() + 1];
        for (int index = 0; index < dependencies.size(); index++) {
            arguments[index] = dependencies.get(index).id();
        }
        arguments[arguments.length - 1] = targetFlagId;
        return Boolean.TRUE.equals(
            jdbc.queryForObject(sql, Boolean.class, arguments));
    }

    private boolean engagementBelongsTo(
        UUID organizationId,
        UUID engagementId
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM engagements engagement
                WHERE engagement.id = ?
                  AND ? IN (
                      engagement.client_organization_id,
                      engagement.vendor_organization_id,
                      engagement.procurement_organization_id)
            )
            """, Boolean.class, engagementId, organizationId));
    }

    private void validateScope(
        String scope,
        UUID organizationId,
        UUID engagementId
    ) {
        boolean valid = switch (scope) {
            case "SYSTEM" ->
                organizationId == null && engagementId == null;
            case "ORGANIZATION" ->
                organizationId != null && engagementId == null;
            case "ENGAGEMENT" ->
                organizationId != null && engagementId != null;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                "Feature flag scope identifiers are inconsistent.");
        }
        if ("ENGAGEMENT".equals(scope)
            && !engagementBelongsTo(organizationId, engagementId)) {
            throw new IllegalArgumentException(
                "Engagement is outside the requested organization.");
        }
    }

    private Map<String, Object> authority(
        String scope,
        UUID organizationId,
        UUID engagementId,
        String permission
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scopeType", scope);
        result.put("organizationId", organizationId);
        result.put("engagementId", engagementId);
        result.put("permission", permission);
        result.put("derivedByServer", true);
        return result;
    }

    private record Flag(UUID id, String key, boolean defaultEnabled) {
    }

    private record Prior(UUID id, int version) {
    }

    private record Effective(
        UUID id,
        int version,
        String scopeType,
        boolean enabled
    ) {
    }

    private record Evaluation(
        boolean enabled,
        String source,
        Integer version
    ) {
    }

    private record Idempotency(String requestHash, UUID resultId) {
    }
}

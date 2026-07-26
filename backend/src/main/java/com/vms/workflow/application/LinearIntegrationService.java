package com.vms.workflow.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.LinearDtos.IssueCurrentView;
import com.vms.workflow.api.LinearDtos.IssueLinkView;
import com.vms.workflow.api.LinearDtos.IssueSnapshotView;
import com.vms.workflow.api.LinearDtos.LinearHealthView;
import com.vms.workflow.api.LinearDtos.LinkIssueRequest;
import com.vms.workflow.api.LinearDtos.WebhookAcceptedView;
import com.vms.workflow.api.LinearDtos.WebhookProcessView;
import com.vms.workflow.security.DeliveryAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class LinearIntegrationService {
    public static final int MAX_WEBHOOK_BYTES = 262_144;
    private static final long REPLAY_WINDOW_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DeliveryAuthorizationService authorization;
    private final WebhookSecretResolver secretResolver;
    private final Clock clock = Clock.systemUTC();

    public LinearIntegrationService(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        DeliveryAuthorizationService authorization,
        WebhookSecretResolver secretResolver
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.secretResolver = secretResolver;
    }

    @Transactional
    public IssueLinkView link(String subject, LinkIssueRequest request) {
        authorization.requireDeliverableVersion(
            subject, request.deliverableVersionId(),
            DeliveryAuthorizationService.LINEAR_MANAGE);
        authorization.requireConnection(
            subject, request.connectionId(), DeliveryAuthorizationService.LINEAR_MANAGE);
        VersionState version = jdbc.query("""
            SELECT version.id, version.state, plan.current_version_id,
                   month.engagement_id
            FROM delivery_deliverable_versions deliverable
            JOIN delivery_plan_versions version ON version.id = deliverable.plan_version_id
            JOIN delivery_plans plan ON plan.id = version.plan_id
            JOIN engagement_months month ON month.id = plan.engagement_month_id
            WHERE deliverable.id = ?
            """, rs -> rs.next()
                ? new VersionState(
                    rs.getObject("id", UUID.class),
                    rs.getString("state"),
                    rs.getObject("current_version_id", UUID.class),
                    rs.getObject("engagement_id", UUID.class))
                : null, request.deliverableVersionId());
        if (version == null) {
            throw notFound();
        }
        if (!"DRAFT".equals(version.state())
            || !version.versionId().equals(version.currentVersionId())) {
            throw new DomainConflictException("Only the current draft plan can be linked.");
        }
        Connection connection = connection(request.connectionId());
        if (!"CONNECTED".equals(connection.status())
            || !version.engagementId().equals(connection.engagementId())) {
            throw notFound();
        }
        RecordedIssue issue = recordedIssue(request.connectionId(), request.issueUuid());
        if (!connection.providerOrganizationId().equals(issue.providerOrganizationId())
            || connection.providerTeamId() == null
            || !connection.providerTeamId().equals(issue.providerTeamId())) {
            throw notFound();
        }
        validateIssueReference(issue.identifier(), issue.url());
        int existingLinks = jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_issue_links
            WHERE deliverable_version_id = ? AND status = 'ACTIVE'
            """, Integer.class, request.deliverableVersionId());
        if (existingLinks > 0
            && (request.rationale() == null || request.rationale().isBlank())) {
            throw new DomainConflictException(
                "MULTI_LINK_RATIONALE_REQUIRED");
        }
        String normalized = normalizedState(
            request.connectionId(), issue.stateType(), issue.stateCategory());
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.update("""
            INSERT INTO linear_issue_current
                (connection_id, linear_issue_uuid, identifier, issue_url, title,
                 provider_state_id, provider_state_name, provider_state_type,
                 provider_state_category, normalized_state, provider_updated_at,
                 fetched_at, payload_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (connection_id, linear_issue_uuid) DO UPDATE SET
                identifier = EXCLUDED.identifier,
                issue_url = EXCLUDED.issue_url,
                title = EXCLUDED.title,
                provider_state_id = EXCLUDED.provider_state_id,
                provider_state_name = EXCLUDED.provider_state_name,
                provider_state_type = EXCLUDED.provider_state_type,
                provider_state_category = EXCLUDED.provider_state_category,
                normalized_state = EXCLUDED.normalized_state,
                provider_updated_at = EXCLUDED.provider_updated_at,
                fetched_at = EXCLUDED.fetched_at,
                payload_hash = EXCLUDED.payload_hash,
                stale = FALSE,
                inaccessible = FALSE
            """, request.connectionId(), request.issueUuid(), issue.identifier(),
            issue.url(), issue.title(), issue.stateId(), issue.stateName(),
            issue.stateType(), issue.stateCategory(), normalized,
            issue.providerUpdatedAt(), now, issue.payloadHash());
        UUID linkId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO linear_issue_links
                (id, deliverable_version_id, connection_id, linear_issue_uuid,
                 identifier, issue_url, multi_link_rationale, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, linkId, request.deliverableVersionId(), request.connectionId(),
            request.issueUuid(), issue.identifier(), issue.url(), request.rationale(), subject);
        recomputeDeliverableProjection(request.deliverableVersionId(), null);
        return linkView(linkId);
    }

    public IssueCurrentView current(String subject, UUID linkId) {
        authorization.requireIssueLink(
            subject, linkId, DeliveryAuthorizationService.LINEAR_READ);
        return jdbc.query("""
            SELECT current.linear_issue_uuid, current.identifier, current.issue_url,
                   current.title, current.provider_state_id, current.provider_state_name,
                   current.provider_state_type, current.provider_state_category,
                   current.normalized_state, current.provider_updated_at, current.fetched_at,
                   current.payload_hash, current.stale, current.inaccessible,
                   COALESCE(projection.execution_projection, 'UNKNOWN')
                       AS execution_projection
            FROM linear_issue_links link
            JOIN linear_issue_current current
              ON current.connection_id = link.connection_id
             AND current.linear_issue_uuid = link.linear_issue_uuid
            JOIN delivery_deliverable_versions deliverable
              ON deliverable.id = link.deliverable_version_id
            LEFT JOIN delivery_execution_projections projection
              ON projection.deliverable_version_id = deliverable.id
            WHERE link.id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw notFound();
                }
                return new IssueCurrentView(
                    rs.getObject("linear_issue_uuid", UUID.class),
                    rs.getString("identifier"),
                    rs.getString("issue_url"),
                    rs.getString("title"),
                    rs.getString("provider_state_id"),
                    rs.getString("provider_state_name"),
                    rs.getString("provider_state_type"),
                    rs.getString("provider_state_category"),
                    rs.getString("normalized_state"),
                    rs.getObject("provider_updated_at", OffsetDateTime.class),
                    rs.getObject("fetched_at", OffsetDateTime.class),
                    rs.getString("payload_hash"),
                    rs.getBoolean("stale"),
                    rs.getBoolean("inaccessible"),
                    rs.getString("execution_projection")
                );
            }, linkId);
    }

    public List<IssueSnapshotView> snapshots(String subject, UUID linkId) {
        authorization.requireIssueLink(
            subject, linkId, DeliveryAuthorizationService.LINEAR_READ);
        return jdbc.query("""
            SELECT id, snapshot_type, status, normalized_state, provider_state,
                   fetched_at, payload_hash, confidence, failure_reason
            FROM linear_issue_snapshots
            WHERE issue_link_id = ?
            ORDER BY created_at
            """, (rs, rowNum) -> {
                JsonNode provider = readJson(rs.getString("provider_state"));
                return new IssueSnapshotView(
                    rs.getObject("id", UUID.class),
                    rs.getString("snapshot_type"),
                    rs.getString("status"),
                    rs.getString("normalized_state"),
                    text(provider, "id"),
                    text(provider, "name"),
                    text(provider, "type"),
                    text(provider, "category"),
                    rs.getObject("fetched_at", OffsetDateTime.class),
                    rs.getString("payload_hash"),
                    rs.getString("confidence"),
                    rs.getString("failure_reason")
                );
            }, linkId);
    }

    public LinearHealthView health(String subject, UUID engagementId) {
        authorization.requireEngagement(
            subject, engagementId, DeliveryAuthorizationService.LINEAR_READ);
        return jdbc.query("""
            SELECT connection.id, connection.status,
                   connection.provider_registration_status,
                   connection.last_verified_delivery_at,
                   connection.last_reconciled_at,
                   connection.last_error_code,
                   (SELECT COUNT(*) FROM linear_issue_links link
                    WHERE link.connection_id = connection.id) AS linked_count,
                   (SELECT COUNT(*) FROM linear_issue_current current
                    WHERE current.connection_id = connection.id
                      AND (current.stale OR current.inaccessible)) AS stale_count,
                   (SELECT COUNT(*) FROM linear_webhook_queue queue
                    JOIN linear_webhook_deliveries delivery
                      ON delivery.delivery_id = queue.delivery_id
                    WHERE delivery.connection_id = connection.id
                      AND queue.status IN ('QUEUED', 'PROCESSING')) AS queued_count,
                   (SELECT COUNT(*) FROM linear_webhook_queue queue
                    JOIN linear_webhook_deliveries delivery
                      ON delivery.delivery_id = queue.delivery_id
                    WHERE delivery.connection_id = connection.id
                      AND queue.status = 'DEAD_LETTER') AS dead_count
            FROM linear_connections connection
            WHERE connection.engagement_id = ?
            ORDER BY connection.created_at DESC
            LIMIT 1
            """, rs -> {
                if (!rs.next()) {
                    return new LinearHealthView(
                        null, "NOT_CONFIGURED", "EXTERNALLY_BLOCKED",
                        null, null, 0, 0, 0, 0, "PROVIDER_NOT_CONFIGURED");
                }
                return new LinearHealthView(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getString("provider_registration_status"),
                    rs.getObject("last_verified_delivery_at", OffsetDateTime.class),
                    rs.getObject("last_reconciled_at", OffsetDateTime.class),
                    rs.getInt("linked_count"),
                    rs.getInt("stale_count"),
                    rs.getInt("queued_count"),
                    rs.getInt("dead_count"),
                    rs.getString("last_error_code")
                );
            }, engagementId);
    }

    @Transactional
    public WebhookAcceptedView receiveWebhook(
        UUID connectionId,
        String signature,
        String timestampHeader,
        String deliveryHeader,
        byte[] rawBody
    ) {
        if (rawBody.length == 0 || rawBody.length > MAX_WEBHOOK_BYTES) {
            throw new IllegalArgumentException("Webhook payload is empty or exceeds 262144 bytes.");
        }
        Connection connection = connection(connectionId);
        if (!"CONNECTED".equals(connection.status())) {
            throw new DomainConflictException("Linear connection is not accepting callbacks.");
        }
        Instant headerTime = parseProviderTimestamp(timestampHeader);
        UUID deliveryId;
        try {
            deliveryId = UUID.fromString(deliveryHeader);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Linear-Delivery must be a UUID.", exception);
        }
        Instant now = Instant.now(clock);
        if (Math.abs(now.getEpochSecond() - headerTime.getEpochSecond())
            > REPLAY_WINDOW_SECONDS) {
            throw new IllegalArgumentException("Webhook timestamp is outside the replay window.");
        }
        WebhookSecretResolver.SecretKeys secretKeys =
            secretResolver.resolve(connection.webhookSecretRef());
        if (connection.webhookSecretRef() == null
            || secretKeys.current().length == 0) {
            throw new DomainConflictException("Linear webhook verification is not configured.");
        }
        verifySignature(signature, rawBody, secretKeys);
        JsonNode payload = parsePayload(rawBody);
        Instant bodyTime = parseBodyTimestamp(payload.path("webhookTimestamp"));
        if (Math.abs(now.getEpochSecond() - bodyTime.getEpochSecond())
                > REPLAY_WINDOW_SECONDS
            || Math.abs(headerTime.getEpochSecond() - bodyTime.getEpochSecond())
                > REPLAY_WINDOW_SECONDS) {
            throw new IllegalArgumentException("Webhook timestamp is outside the replay window.");
        }
        if (!connection.providerOrganizationId().equals(text(payload, "organizationId"))) {
            throw new IllegalArgumentException("Webhook organization does not match the connection.");
        }
        String payloadConnection = text(payload, "connectionId");
        if (payloadConnection != null && !connectionId.toString().equals(payloadConnection)) {
            throw new IllegalArgumentException("Webhook connection does not match the receiver.");
        }
        String payloadHash = sha256(rawBody);
        String fingerprint = sha256(connectionId + "|" + payloadHash);
        ExistingDelivery existing = jdbc.query("""
            SELECT connection_id, payload_hash
            FROM linear_webhook_deliveries
            WHERE delivery_id = ?
            """, rs -> rs.next()
                ? new ExistingDelivery(
                    rs.getObject("connection_id", UUID.class),
                    rs.getString("payload_hash"))
                : null, deliveryId);
        if (existing != null) {
            if (!connectionId.equals(existing.connectionId())
                || !payloadHash.equals(existing.payloadHash())) {
                throw new DomainConflictException(
                    "Webhook delivery identifier was reused with different content.");
            }
            return new WebhookAcceptedView(deliveryId, true, "DEDUPLICATED");
        }
        jdbc.update("""
            INSERT INTO linear_webhook_deliveries
                (delivery_id, connection_id, event_fingerprint,
                 signature_verified_at, provider_timestamp, payload_hash,
                 raw_payload, raw_body)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?::jsonb, ?)
            """, deliveryId, connectionId, fingerprint,
            OffsetDateTime.ofInstant(headerTime, ZoneOffset.UTC),
            payloadHash, new String(rawBody, StandardCharsets.UTF_8), rawBody);
        jdbc.update("""
            INSERT INTO linear_webhook_queue (id, delivery_id)
            VALUES (?, ?)
            """, UUID.randomUUID(), deliveryId);
        jdbc.update("""
            UPDATE linear_connections
            SET last_verified_delivery_at = CURRENT_TIMESTAMP,
                last_error_code = NULL
            WHERE id = ?
            """, connectionId);
        return new WebhookAcceptedView(deliveryId, false, "QUEUED");
    }

    @Transactional
    public WebhookProcessView process(String subject, UUID deliveryId) {
        QueueWork work = jdbc.query("""
            SELECT queue.id, queue.status, queue.attempt_count,
                   queue.processed_at, delivery.connection_id,
                   delivery.event_fingerprint, delivery.payload_hash,
                   delivery.raw_body
            FROM linear_webhook_queue queue
            JOIN linear_webhook_deliveries delivery
              ON delivery.delivery_id = queue.delivery_id
            WHERE queue.delivery_id = ?
            FOR UPDATE OF queue
            """, rs -> rs.next()
                ? new QueueWork(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getObject("processed_at", OffsetDateTime.class),
                    rs.getObject("connection_id", UUID.class),
                    rs.getString("event_fingerprint"),
                    rs.getString("payload_hash"),
                    parsePayload(rs.getBytes("raw_body")))
                : null, deliveryId);
        if (work == null) {
            throw notFound();
        }
        authorization.requireConnection(
            subject, work.connectionId(), DeliveryAuthorizationService.LINEAR_REPLAY);
        if ("PROCESSED".equals(work.status())) {
            return new WebhookProcessView(
                deliveryId, work.queueId(), work.status(), work.attemptCount(),
                work.processedAt(), true);
        }
        jdbc.update("""
            UPDATE linear_webhook_queue
            SET status = 'PROCESSING', attempt_count = attempt_count + 1
            WHERE id = ?
            """, work.queueId());
        JsonNode payload = work.payload();
        if (!"Issue".equals(text(payload, "type"))
            || !payload.path("data").isObject()) {
            jdbc.update("""
                UPDATE linear_webhook_queue
                SET status = 'QUARANTINED', last_error_code = 'UNSUPPORTED_EVENT'
                WHERE id = ?
                """, work.queueId());
            return new WebhookProcessView(
                deliveryId, work.queueId(), "QUARANTINED",
                work.attemptCount() + 1, null, false);
        }
        JsonNode data = payload.path("data");
        UUID issueUuid;
        try {
            issueUuid = UUID.fromString(text(data, "id"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Webhook issue id must be a UUID.", exception);
        }
        JsonNode state = data.path("state");
        String normalized = normalizedState(
            work.connectionId(), text(state, "type"), text(state, "category"));
        OffsetDateTime providerUpdatedAt =
            parseOffsetDateTime(requiredText(data, "updatedAt"));
        OffsetDateTime currentUpdatedAt = jdbc.query("""
            SELECT provider_updated_at
            FROM linear_issue_current
            WHERE connection_id = ? AND linear_issue_uuid = ?
            FOR UPDATE
            """, rs -> rs.next()
                ? rs.getObject("provider_updated_at", OffsetDateTime.class)
                : null, work.connectionId(), issueUuid);
        boolean stale = currentUpdatedAt != null
            && providerUpdatedAt.isBefore(currentUpdatedAt);
        UUID eventId = UUID.randomUUID();
        int eventInserted = jdbc.update("""
            INSERT INTO linear_issue_events
                (id, connection_id, linear_issue_uuid, delivery_id,
                 event_fingerprint, event_action, normalized_state,
                 provider_state, payload_hash, occurred_at, processing_disposition)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, eventId, work.connectionId(), issueUuid, deliveryId,
            work.eventFingerprint(), nullToEmpty(text(payload, "action")),
            normalized, state.toString(), work.payloadHash(),
            OffsetDateTime.ofInstant(parseBodyTimestamp(
                payload.path("webhookTimestamp")), ZoneOffset.UTC),
            stale ? "STALE_IGNORED" : "APPLIED");
        if (stale) {
            jdbc.update("""
                INSERT INTO linear_webhook_audit_events
                    (delivery_id, connection_id, event_type, facts)
                VALUES (?, ?, 'OUT_OF_ORDER_EVENT_IGNORED',
                        jsonb_build_object(
                            'issueId', ?::text,
                            'providerUpdatedAt', ?::text,
                            'currentUpdatedAt', ?::text))
                """, deliveryId, work.connectionId(), issueUuid.toString(),
                providerUpdatedAt.toString(), currentUpdatedAt.toString());
        } else {
            jdbc.update("""
            INSERT INTO linear_issue_current
                (connection_id, linear_issue_uuid, identifier, issue_url, title,
                 provider_state_id, provider_state_name, provider_state_type,
                 provider_state_category, normalized_state, provider_updated_at,
                 fetched_at, payload_hash, stale, inaccessible)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, FALSE, FALSE)
            ON CONFLICT (connection_id, linear_issue_uuid) DO UPDATE SET
                identifier = EXCLUDED.identifier,
                issue_url = EXCLUDED.issue_url,
                title = EXCLUDED.title,
                provider_state_id = EXCLUDED.provider_state_id,
                provider_state_name = EXCLUDED.provider_state_name,
                provider_state_type = EXCLUDED.provider_state_type,
                provider_state_category = EXCLUDED.provider_state_category,
                normalized_state = EXCLUDED.normalized_state,
                provider_updated_at = EXCLUDED.provider_updated_at,
                fetched_at = CURRENT_TIMESTAMP,
                payload_hash = EXCLUDED.payload_hash,
                stale = FALSE,
                inaccessible = FALSE
            """, work.connectionId(), issueUuid, requiredText(data, "identifier"),
            requiredText(data, "url"), requiredText(data, "title"),
            text(state, "id"), text(state, "name"), text(state, "type"),
            text(state, "category"), normalized, providerUpdatedAt, work.payloadHash());
            if (eventInserted > 0) {
                jdbc.queryForList("""
                    SELECT deliverable_version_id
                    FROM linear_issue_links
                    WHERE connection_id = ? AND linear_issue_uuid = ?
                    """, UUID.class, work.connectionId(), issueUuid)
                    .forEach(id -> recomputeDeliverableProjection(id, eventId));
            }
        }
        jdbc.update("""
            UPDATE linear_webhook_queue
            SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP,
                last_error_code = NULL
            WHERE id = ?
            """, work.queueId());
        return queueView(deliveryId, false);
    }

    public List<IssueLinkView> linkViews(UUID deliverableVersionId) {
        return jdbc.query("""
            SELECT link.id, link.deliverable_version_id, link.connection_id,
                   link.linear_issue_uuid, link.identifier, link.issue_url,
                   link.status, link.multi_link_rationale,
                   current.normalized_state, current.fetched_at
            FROM linear_issue_links link
            LEFT JOIN linear_issue_current current
              ON current.connection_id = link.connection_id
             AND current.linear_issue_uuid = link.linear_issue_uuid
            WHERE link.deliverable_version_id = ?
            ORDER BY link.identifier
            """, (rs, rowNum) -> issueLinkView(rs), deliverableVersionId);
    }

    private WebhookProcessView queueView(UUID deliveryId, boolean duplicate) {
        return jdbc.query("""
            SELECT id, status, attempt_count, processed_at
            FROM linear_webhook_queue WHERE delivery_id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw notFound();
                }
                return new WebhookProcessView(
                    deliveryId,
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getObject("processed_at", OffsetDateTime.class),
                    duplicate
                );
            }, deliveryId);
    }

    private IssueLinkView linkView(UUID linkId) {
        return jdbc.query("""
            SELECT link.id, link.deliverable_version_id, link.connection_id,
                   link.linear_issue_uuid, link.identifier, link.issue_url,
                   link.status, link.multi_link_rationale,
                   current.normalized_state, current.fetched_at
            FROM linear_issue_links link
            LEFT JOIN linear_issue_current current
              ON current.connection_id = link.connection_id
             AND current.linear_issue_uuid = link.linear_issue_uuid
            WHERE link.id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw notFound();
                }
                return issueLinkView(rs);
            }, linkId);
    }

    private IssueLinkView issueLinkView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IssueLinkView(
            rs.getObject("id", UUID.class),
            rs.getObject("deliverable_version_id", UUID.class),
            rs.getObject("connection_id", UUID.class),
            rs.getObject("linear_issue_uuid", UUID.class),
            rs.getString("identifier"),
            rs.getString("issue_url"),
            rs.getString("status"),
            rs.getString("multi_link_rationale"),
            rs.getString("normalized_state"),
            rs.getObject("fetched_at", OffsetDateTime.class)
        );
    }

    private void recomputeDeliverableProjection(
        UUID deliverableVersionId,
        UUID sourceEventId
    ) {
        String projection = jdbc.queryForObject("""
            SELECT CASE
                WHEN COUNT(*) = 0 THEN 'UNKNOWN'
                WHEN BOOL_AND(current.normalized_state = 'COMPLETED') THEN 'COMPLETED'
                WHEN BOOL_OR(current.normalized_state = 'STARTED') THEN 'STARTED'
                WHEN BOOL_OR(current.normalized_state = 'UNSTARTED') THEN 'UNSTARTED'
                WHEN BOOL_OR(current.normalized_state = 'BACKLOG') THEN 'BACKLOG'
                WHEN BOOL_OR(current.normalized_state = 'CANCELED') THEN 'CANCELED'
                ELSE 'UNKNOWN'
            END
            FROM linear_issue_links link
            JOIN linear_issue_current current
              ON current.connection_id = link.connection_id
             AND current.linear_issue_uuid = link.linear_issue_uuid
            WHERE link.deliverable_version_id = ?
            """, String.class, deliverableVersionId);
        jdbc.update("""
            INSERT INTO delivery_execution_projections
                (deliverable_version_id, execution_projection, source_event_id, updated_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (deliverable_version_id) DO UPDATE SET
                execution_projection = EXCLUDED.execution_projection,
                source_event_id = EXCLUDED.source_event_id,
                updated_at = CURRENT_TIMESTAMP
            """, deliverableVersionId, projection, sourceEventId);
    }

    private String normalizedState(UUID connectionId, String stateType, String category) {
        if (stateType == null || stateType.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = jdbc.query("""
            SELECT mapping.normalized_state
            FROM linear_connections connection
            JOIN linear_state_mappings mapping
              ON mapping.connection_id = connection.id
             AND mapping.mapping_version = connection.mapping_version
            WHERE connection.id = ?
              AND LOWER(mapping.provider_state_type) = LOWER(?)
              AND mapping.provider_state_category IN (?, '')
            ORDER BY CASE WHEN mapping.provider_state_category = ? THEN 0 ELSE 1 END
            LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null,
            connectionId, stateType, nullToEmpty(category), nullToEmpty(category));
        if (normalized == null) {
            Connection value = connection(connectionId);
            if (!"CONNECTED".equals(value.status())) {
                throw new DomainConflictException("Linear connection requires action.");
            }
            return "UNKNOWN";
        }
        return normalized;
    }

    private Connection connection(UUID connectionId) {
        Connection result = jdbc.query("""
            SELECT engagement_id, provider_organization_id, provider_team_id,
                   status, webhook_secret_ref
            FROM linear_connections WHERE id = ?
            """, rs -> rs.next()
                ? new Connection(
                    rs.getObject("engagement_id", UUID.class),
                    rs.getString("provider_organization_id"),
                    rs.getString("provider_team_id"),
                    rs.getString("status"),
                    rs.getString("webhook_secret_ref"))
                : null, connectionId);
        if (result == null) {
            throw notFound();
        }
        return result;
    }

    private RecordedIssue recordedIssue(UUID connectionId, UUID issueUuid) {
        RecordedIssue result = jdbc.query("""
            SELECT provider_organization_id, provider_team_id, identifier,
                   issue_url, title, provider_state_id, provider_state_name,
                   provider_state_type, provider_state_category,
                   provider_updated_at, payload_hash
            FROM linear_recorded_issue_metadata
            WHERE connection_id = ? AND linear_issue_uuid = ?
            """, rs -> rs.next()
                ? new RecordedIssue(
                    rs.getString("provider_organization_id"),
                    rs.getString("provider_team_id"),
                    rs.getString("identifier"),
                    rs.getString("issue_url"),
                    rs.getString("title"),
                    rs.getString("provider_state_id"),
                    rs.getString("provider_state_name"),
                    rs.getString("provider_state_type"),
                    rs.getString("provider_state_category"),
                    rs.getObject("provider_updated_at", OffsetDateTime.class),
                    rs.getString("payload_hash"))
                : null, connectionId, issueUuid);
        if (result == null) {
            throw notFound();
        }
        return result;
    }

    private void validateIssueReference(String identifier, String url) {
        if (!identifier.matches("[A-Z][A-Z0-9_]*-[1-9][0-9]*")) {
            throw new IllegalArgumentException("Linear identifier is invalid.");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Linear URL is invalid.", exception);
        }
        String host = uri.getHost();
        if (!"linear.app".equalsIgnoreCase(host)
            && (host == null || !host.toLowerCase(Locale.ROOT).endsWith(".linear.app"))) {
            throw new IllegalArgumentException("Only Linear issue URLs are supported.");
        }
    }

    private void verifySignature(
        String providedSignature,
        byte[] rawBody,
        WebhookSecretResolver.SecretKeys secretKeys
    ) {
        if (providedSignature == null || providedSignature.isBlank()) {
            throw new IllegalArgumentException("Linear-Signature is required.");
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(providedSignature.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Linear-Signature must be hexadecimal.", exception);
        }
        try {
            boolean valid = signatureMatches(secretKeys.current(), rawBody, provided);
            for (byte[] previous : secretKeys.previous()) {
                valid |= signatureMatches(previous, rawBody, provided);
            }
            if (!valid) {
                throw new IllegalArgumentException("Linear webhook signature is invalid.");
            }
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }

    private boolean signatureMatches(byte[] secret, byte[] rawBody, byte[] provided)
        throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return MessageDigest.isEqual(mac.doFinal(rawBody), provided);
    }

    private JsonNode parsePayload(byte[] rawBody) {
        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            if (!payload.isObject()) {
                throw new IllegalArgumentException("Webhook body must be a JSON object.");
            }
            return payload;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Webhook body is malformed JSON.", exception);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored JSON is invalid.", exception);
        }
    }

    private Instant parseProviderTimestamp(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Linear-Timestamp is required.");
        }
        try {
            long numeric = Long.parseLong(value);
            return numeric > 10_000_000_000L
                ? Instant.ofEpochMilli(numeric)
                : Instant.ofEpochSecond(numeric);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Linear-Timestamp is invalid.", exception);
            }
        }
    }

    private Instant parseBodyTimestamp(JsonNode value) {
        if (value.isIntegralNumber()) {
            long numeric = value.longValue();
            return numeric > 10_000_000_000L
                ? Instant.ofEpochMilli(numeric)
                : Instant.ofEpochSecond(numeric);
        }
        if (value.isTextual()) {
            return parseProviderTimestamp(value.textValue());
        }
        throw new IllegalArgumentException("webhookTimestamp is required.");
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (value == null || value.isBlank()) {
            return OffsetDateTime.now(clock);
        }
        return OffsetDateTime.parse(value);
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String requiredText(JsonNode value, String field) {
        String result = text(value, field);
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return result;
    }

    private String text(JsonNode value, String field) {
        JsonNode child = value.path(field);
        return child.isTextual() ? child.textValue() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    private record VersionState(
        UUID versionId,
        String state,
        UUID currentVersionId,
        UUID engagementId
    ) {
    }

    private record Connection(
        UUID engagementId,
        String providerOrganizationId,
        String providerTeamId,
        String status,
        String webhookSecretRef
    ) {
    }

    private record RecordedIssue(
        String providerOrganizationId,
        String providerTeamId,
        String identifier,
        String url,
        String title,
        String stateId,
        String stateName,
        String stateType,
        String stateCategory,
        OffsetDateTime providerUpdatedAt,
        String payloadHash
    ) {
    }

    private record ExistingDelivery(UUID connectionId, String payloadHash) {
    }

    private record QueueWork(
        UUID queueId,
        String status,
        int attemptCount,
        OffsetDateTime processedAt,
        UUID connectionId,
        String eventFingerprint,
        String payloadHash,
        JsonNode payload
    ) {
    }
}

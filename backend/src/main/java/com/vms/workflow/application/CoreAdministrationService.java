package com.vms.workflow.application;

import com.vms.workflow.api.CoreAdministrationDtos.AddContactMemberInput;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalPolicyView;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalActionInput;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalActionView;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalRequestView;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalStageInput;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalStageView;
import com.vms.workflow.api.CoreAdministrationDtos.ConfigurationView;
import com.vms.workflow.api.CoreAdministrationDtos.ContactGroupView;
import com.vms.workflow.api.CoreAdministrationDtos.ContactMemberView;
import com.vms.workflow.api.CoreAdministrationDtos.CreateApprovalPolicyInput;
import com.vms.workflow.api.CoreAdministrationDtos.CreateApprovalRequestInput;
import com.vms.workflow.api.CoreAdministrationDtos.CreateContactGroupInput;
import com.vms.workflow.api.CoreAdministrationDtos.CreateDelegationInput;
import com.vms.workflow.api.CoreAdministrationDtos.DelegationView;
import com.vms.workflow.api.CoreAdministrationDtos.EligibleUserView;
import com.vms.workflow.api.CoreAdministrationDtos.EngagementAdministrationView;
import com.vms.workflow.api.CoreAdministrationDtos.MonthTransitionInput;
import com.vms.workflow.api.CoreAdministrationDtos.MonthTransitionView;
import com.vms.workflow.api.CoreAdministrationDtos.PublishApprovalPolicyInput;
import com.vms.workflow.api.CoreAdministrationDtos.PublishConfigurationInput;
import com.vms.workflow.api.CoreAdministrationDtos.RevokeDelegationInput;
import com.vms.workflow.api.CoreAdministrationDtos.ReviseApprovalPolicyInput;
import com.vms.workflow.api.CoreAdministrationDtos.UpdateEngagementInput;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.security.TenantAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CoreAdministrationService {
    private static final Set<String> REOPEN_REQUEST_STATES = Set.of(
        "PLAN_APPROVED", "ACTIVE", "DELIVERY_SUBMITTED", "DELIVERY_REVIEW",
        "CONFIRMATION_PENDING", "CONFIRMED", "INVOICE_READY",
        "INVOICE_SUBMITTED", "CLOSED");

    private final JdbcTemplate jdbc;
    private final TenantAuthorizationService authorization;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CoreAdministrationService(
        JdbcTemplate jdbc,
        TenantAuthorizationService authorization,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EngagementAdministrationView engagement(
        String subject,
        UUID engagementId
    ) {
        authorization.requireEngagement(subject, engagementId);
        return engagementRow(engagementId, false);
    }

    @Transactional
    public EngagementAdministrationView updateEngagement(
        String subject,
        UUID engagementId,
        UpdateEngagementInput input,
        UUID correlationId
    ) {
        authorization.requireEngagementPermission(
            subject, engagementId, "engagement.update");
        EngagementAdministrationView current = engagementRow(engagementId, true);
        requireVersion(
            "ENGAGEMENT_VERSION_CONFLICT",
            current.version(),
            input.expectedVersion());
        int updated = jdbc.update("""
            UPDATE engagements
            SET name = ?, status = ?, default_project_id = ?,
                admin_version = admin_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND admin_version = ?
            """,
            input.name().trim(),
            input.status(),
            input.defaultProjectId(),
            engagementId,
            input.expectedVersion());
        if (updated != 1) {
            throw staleEngagement(engagementId);
        }
        audit(
            engagementId, null, "ENGAGEMENT_UPDATED", subject,
            "engagement", engagementId, input.expectedVersion() + 1,
            correlationId,
            Map.of(
                "name", input.name().trim(),
                "status", input.status(),
                "defaultProjectId",
                String.valueOf(input.defaultProjectId())));
        return engagementRow(engagementId, false);
    }

    @Transactional(readOnly = true)
    public List<ConfigurationView> configurations(
        String subject,
        UUID engagementId
    ) {
        authorization.requireEngagement(subject, engagementId);
        return jdbc.query("""
            SELECT id, engagement_id, version, status, valid_from, valid_to,
                   timezone, planning_due_day, certification_due_day,
                   confirmation_due_day, reopen_policy::text,
                   notification_policy::text, published_at
            FROM engagement_configuration_versions
            WHERE engagement_id = ?
            ORDER BY version DESC
            """, (rs, row) -> configuration(rs), engagementId);
    }

    @Transactional(readOnly = true)
    public ConfigurationView effectiveConfiguration(
        String subject,
        UUID engagementId,
        LocalDate effectiveOn
    ) {
        authorization.requireEngagement(subject, engagementId);
        ConfigurationView result = jdbc.query("""
            SELECT id, engagement_id, version, status, valid_from, valid_to,
                   timezone, planning_due_day, certification_due_day,
                   confirmation_due_day, reopen_policy::text,
                   notification_policy::text, published_at
            FROM engagement_configuration_versions
            WHERE engagement_id = ?
              AND status = 'PUBLISHED'
              AND valid_from <= ?
              AND (valid_to IS NULL OR valid_to >= ?)
            ORDER BY valid_from DESC, version DESC
            LIMIT 1
            """, rs -> rs.next() ? configuration(rs) : null,
            engagementId, effectiveOn, effectiveOn);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    @Transactional
    public ConfigurationView publishConfiguration(
        String subject,
        UUID engagementId,
        PublishConfigurationInput input,
        UUID correlationId
    ) {
        authorization.requireEngagementPermission(
            subject, engagementId, "engagement.configure");
        EngagementAdministrationView engagement =
            engagementRow(engagementId, true);
        requireVersion(
            "ENGAGEMENT_VERSION_CONFLICT",
            engagement.version(),
            input.expectedEngagementVersion());
        validateDateWindow(input.validFrom(), input.validTo());
        validateDueDay(input.planningDueDay());
        validateDueDay(input.certificationDueDay());
        validateDueDay(input.confirmationDueDay());
        Boolean overlaps = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM engagement_configuration_versions existing
                WHERE existing.engagement_id = ?
                  AND existing.status = 'PUBLISHED'
                  AND (
                      (
                          existing.valid_from < ?
                          AND existing.valid_to IS NOT NULL
                          AND existing.valid_to >= ?
                      )
                      OR (
                          existing.valid_from > ?
                          AND (?::date IS NULL
                               OR ?::date >= existing.valid_from)
                      )
                  )
            )
            """, Boolean.class,
            engagementId,
            input.validFrom(), input.validFrom(),
            input.validFrom(), input.validTo(), input.validTo());
        if (Boolean.TRUE.equals(overlaps)) {
            throw new DomainConflictException(
                "CONFIGURATION_EFFECTIVE_WINDOW_CONFLICT",
                "The configuration effective window overlaps another published version.",
                engagement.version());
        }
        Integer nextVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM engagement_configuration_versions
            WHERE engagement_id = ?
            """, Integer.class, engagementId);
        UUID supersedes = engagement.configurationVersionId();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO engagement_configuration_versions(
                id, engagement_id, version, status, valid_from, valid_to,
                timezone, planning_due_day, certification_due_day,
                confirmation_due_day, reopen_policy, notification_policy,
                created_by_subject, published_at, supersedes_id)
            VALUES (?, ?, ?, 'PUBLISHED', ?, ?, ?, ?, ?, ?, ?::jsonb,
                    ?::jsonb, ?, CURRENT_TIMESTAMP, ?)
            """,
            id, engagementId, nextVersion, input.validFrom(), input.validTo(),
            input.timezone(), input.planningDueDay(),
            input.certificationDueDay(), input.confirmationDueDay(),
            json(input.reopenPolicy()), json(input.notificationPolicy()),
            subject, supersedes);
        int updated = jdbc.update("""
            UPDATE engagements
            SET configuration_version_id = ?,
                admin_version = admin_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND admin_version = ?
            """, id, engagementId, input.expectedEngagementVersion());
        if (updated != 1) {
            throw staleEngagement(engagementId);
        }
        audit(
            engagementId, null, "ENGAGEMENT_CONFIGURATION_PUBLISHED",
            subject, "engagement_configuration", id, nextVersion.longValue(),
            correlationId,
            Map.of(
                "effectiveFrom", input.validFrom().toString(),
                "supersedesId", String.valueOf(supersedes)));
        return configuration(id);
    }

    @Transactional(readOnly = true)
    public List<ContactGroupView> contactGroups(
        String subject,
        UUID engagementId
    ) {
        authorization.requireEngagement(subject, engagementId);
        return jdbc.query("""
            SELECT id FROM contact_groups
            WHERE engagement_id = ?
            ORDER BY code
            """, (rs, row) -> contactGroup(
                rs.getObject("id", UUID.class)), engagementId);
    }

    @Transactional
    public ContactGroupView createContactGroup(
        String subject,
        UUID engagementId,
        CreateContactGroupInput input,
        UUID correlationId
    ) {
        authorization.requireEngagementPermission(
            subject, engagementId, "contacts.manage");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO contact_groups(
                id, engagement_id, project_id, code, name, group_type,
                created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            id, engagementId, input.projectId(), input.code().trim(),
            input.name().trim(), input.groupType(), subject);
        audit(
            engagementId, null, "CONTACT_GROUP_CREATED", subject,
            "contact_group", id, 0L, correlationId,
            Map.of("code", input.code().trim(), "type", input.groupType()));
        return contactGroup(id);
    }

    @Transactional
    public ContactGroupView addContactMember(
        String subject,
        UUID groupId,
        AddContactMemberInput input,
        UUID correlationId
    ) {
        GroupContext context = groupContext(groupId, true);
        authorization.requireEngagementPermission(
            subject, context.engagementId(), "contacts.manage");
        requireVersion(
            "CONTACT_GROUP_VERSION_CONFLICT",
            context.version(),
            input.expectedGroupVersion());
        validateDateWindow(input.validFrom(), input.validTo());
        if (input.userProfileId() != null) {
            Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM user_profiles user_profile
                    JOIN memberships membership
                      ON membership.user_profile_id = user_profile.id
                    JOIN engagements engagement ON engagement.id = ?
                    WHERE user_profile.id = ?
                      AND user_profile.status = 'ACTIVE'
                      AND membership.status = 'ACTIVE'
                      AND membership.organization_id IN (
                          engagement.client_organization_id,
                          engagement.vendor_organization_id,
                          engagement.procurement_organization_id)
                )
                """, Boolean.class, context.engagementId(), input.userProfileId());
            if (!Boolean.TRUE.equals(eligible)) {
                throw new IllegalArgumentException(
                    "The selected contact is not eligible in this engagement.");
            }
        }
        UUID memberId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO contact_group_members(
                id, contact_group_id, user_profile_id, email, display_name,
                role_attribution, verified, valid_from, valid_to,
                created_by_subject)
            VALUES (?, ?, ?, lower(?), ?, ?, ?, ?, ?, ?)
            """,
            memberId, groupId, input.userProfileId(), input.email(),
            input.displayName().trim(), input.roleAttribution().trim(),
            input.verified(), input.validFrom(), input.validTo(), subject);
        int updated = jdbc.update("""
            UPDATE contact_groups
            SET version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
            """, groupId, input.expectedGroupVersion());
        if (updated != 1) {
            throw new DomainConflictException(
                "CONTACT_GROUP_VERSION_CONFLICT",
                "The contact group changed before the member was added.",
                groupContext(groupId, false).version());
        }
        audit(
            context.engagementId(), null, "CONTACT_GROUP_MEMBER_ADDED",
            subject, "contact_group_member", memberId,
            input.expectedGroupVersion() + 1, correlationId,
            Map.of(
                "groupId", groupId.toString(),
                "verified", input.verified()));
        return contactGroup(groupId);
    }

    @Transactional(readOnly = true)
    public List<EligibleUserView> eligibleUsers(
        String subject,
        UUID engagementId,
        UUID organizationId
    ) {
        authorization.requireEngagementPermission(
            subject, engagementId, "delegation.manage");
        Boolean participates = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM engagements
                WHERE id = ?
                  AND ? IN (
                      client_organization_id,
                      vendor_organization_id,
                      procurement_organization_id)
            )
            """, Boolean.class, engagementId, organizationId);
        if (!Boolean.TRUE.equals(participates)) {
            throw new EntityNotFoundException("Resource not found.");
        }
        LocalDate today = LocalDate.now(clock);
        return jdbc.query("""
            SELECT user_profile.id, membership.organization_id,
                   user_profile.display_name, user_profile.email,
                   array_agg(DISTINCT role.code ORDER BY role.code)
                     FILTER (WHERE role.code IS NOT NULL) AS role_codes
            FROM user_profiles user_profile
            JOIN memberships membership
              ON membership.user_profile_id = user_profile.id
            LEFT JOIN role_assignments assignment
              ON assignment.user_profile_id = user_profile.id
             AND assignment.organization_id = membership.organization_id
             AND assignment.status = 'ACTIVE'
             AND assignment.valid_from <= ?
             AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
            LEFT JOIN roles role
              ON role.id = assignment.role_id AND role.status = 'ACTIVE'
            WHERE membership.organization_id = ?
              AND user_profile.status = 'ACTIVE'
              AND membership.status = 'ACTIVE'
              AND membership.valid_from <= ?
              AND (membership.valid_to IS NULL OR membership.valid_to >= ?)
            GROUP BY user_profile.id, membership.organization_id,
                     user_profile.display_name, user_profile.email
            ORDER BY user_profile.display_name, user_profile.id
            """, (rs, row) -> new EligibleUserView(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("email"),
                stringArray(rs, "role_codes")),
            today, today, organizationId, today, today);
    }

    @Transactional(readOnly = true)
    public List<ApprovalPolicyView> approvalPolicies(
        String subject,
        UUID engagementId
    ) {
        authorization.requireEngagement(subject, engagementId);
        return jdbc.query("""
            SELECT id FROM approval_policies
            WHERE engagement_id = ?
            ORDER BY code
            """, (rs, row) -> approvalPolicy(
                rs.getObject("id", UUID.class)), engagementId);
    }

    @Transactional
    public ApprovalPolicyView createApprovalPolicy(
        String subject,
        UUID engagementId,
        CreateApprovalPolicyInput input,
        UUID correlationId
    ) {
        authorization.requireEngagementPermission(
            subject, engagementId, "approval.policy.manage");
        validateDateWindow(input.validFrom(), input.validTo());
        validateStages(engagementId, input.projectId(), input.stages());
        UUID policyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO approval_policies(
                id, engagement_id, project_id, code, name, action_type,
                created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            policyId, engagementId, input.projectId(), input.code().trim(),
            input.name().trim(), input.actionType(), subject);
        jdbc.update("""
            INSERT INTO approval_policy_versions(
                id, policy_id, version, status, valid_from, valid_to,
                prohibit_self_approval, evidence_required, rules,
                created_by_subject)
            VALUES (?, ?, 1, 'DRAFT', ?, ?, ?, ?, ?::jsonb, ?)
            """,
            versionId, policyId, input.validFrom(), input.validTo(),
            input.prohibitSelfApproval(), input.evidenceRequired(),
            json(input.rules()), subject);
        insertStages(versionId, input.stages());
        jdbc.update("""
            UPDATE approval_policies
            SET current_version_id = ?
            WHERE id = ?
            """, versionId, policyId);
        audit(
            engagementId, null, "APPROVAL_POLICY_DRAFT_CREATED",
            subject, "approval_policy", policyId, 0L, correlationId,
            Map.of(
                "code", input.code().trim(),
                "stageCount", input.stages().size()));
        return approvalPolicy(policyId);
    }

    @Transactional
    public ApprovalPolicyView publishApprovalPolicy(
        String subject,
        UUID policyId,
        PublishApprovalPolicyInput input,
        UUID correlationId
    ) {
        PolicyContext context = policyContext(policyId, true);
        authorization.requireEngagementPermission(
            subject, context.engagementId(), "approval.policy.manage");
        requireVersion(
            "APPROVAL_POLICY_VERSION_CONFLICT",
            context.adminVersion(),
            input.expectedPolicyVersion());
        if (!"DRAFT".equals(context.versionStatus())) {
            throw new DomainConflictException(
                "APPROVAL_POLICY_NOT_DRAFT",
                "Only a draft approval policy version can be published.",
                context.adminVersion());
        }
        List<ApprovalStageInput> stages = storedStageInputs(
            context.policyVersionId());
        validateStages(
            context.engagementId(), context.projectId(), stages);
        int updated = jdbc.update("""
            UPDATE approval_policies
            SET status = 'ACTIVE', version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
            """, policyId, input.expectedPolicyVersion());
        if (updated != 1) {
            throw new DomainConflictException(
                "APPROVAL_POLICY_VERSION_CONFLICT",
                "The approval policy changed before publication.",
                policyContext(policyId, false).adminVersion());
        }
        jdbc.update("""
            UPDATE approval_policy_versions
            SET valid_to = ? - 1
            WHERE id = (
                SELECT supersedes_id
                FROM approval_policy_versions
                WHERE id = ?
            )
              AND status = 'PUBLISHED'
              AND (valid_to IS NULL OR valid_to >= ?)
            """, context.validFrom(), context.policyVersionId(),
            context.validFrom());
        jdbc.update("""
            UPDATE approval_policy_versions
            SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'DRAFT'
            """, context.policyVersionId());
        audit(
            context.engagementId(), null, "APPROVAL_POLICY_PUBLISHED",
            subject, "approval_policy", policyId,
            input.expectedPolicyVersion() + 1, correlationId,
            Map.of(
                "policyVersionId", context.policyVersionId().toString(),
                "stageCount", stages.size()));
        return approvalPolicy(policyId);
    }

    @Transactional
    public ApprovalPolicyView reviseApprovalPolicy(
        String subject,
        UUID policyId,
        ReviseApprovalPolicyInput input,
        UUID correlationId
    ) {
        PolicyContext context = policyContext(policyId, true);
        authorization.requireEngagementPermission(
            subject, context.engagementId(), "approval.policy.manage");
        requireVersion(
            "APPROVAL_POLICY_VERSION_CONFLICT",
            context.adminVersion(), input.expectedPolicyVersion());
        if (!"PUBLISHED".equals(context.versionStatus())) {
            throw new DomainConflictException(
                "APPROVAL_POLICY_REVISION_NOT_AVAILABLE",
                "Only the current published policy can be revised.",
                context.adminVersion());
        }
        validateDateWindow(input.validFrom(), input.validTo());
        if (!input.validFrom().isAfter(context.validFrom())) {
            throw new IllegalArgumentException(
                "A policy revision must become effective after the current version.");
        }
        validateStages(
            context.engagementId(), context.projectId(), input.stages());
        Integer nextVersion = jdbc.queryForObject("""
            SELECT max(version) + 1
            FROM approval_policy_versions
            WHERE policy_id = ?
            """, Integer.class, policyId);
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO approval_policy_versions(
                id, policy_id, version, status, valid_from, valid_to,
                prohibit_self_approval, evidence_required, rules,
                created_by_subject, supersedes_id)
            VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?::jsonb, ?, ?)
            """, revisionId, policyId, nextVersion,
            input.validFrom(), input.validTo(),
            input.prohibitSelfApproval(), input.evidenceRequired(),
            json(input.rules()), subject, context.policyVersionId());
        insertStages(revisionId, input.stages());
        int updated = jdbc.update("""
            UPDATE approval_policies
            SET name = ?, status = 'ACTIVE', current_version_id = ?,
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND version = ?
            """, input.name().trim(), revisionId, policyId,
            input.expectedPolicyVersion());
        if (updated != 1) {
            throw new DomainConflictException(
                "APPROVAL_POLICY_VERSION_CONFLICT",
                "The approval policy changed before revision.",
                policyContext(policyId, false).adminVersion());
        }
        audit(
            context.engagementId(), null,
            "APPROVAL_POLICY_REVISION_CREATED", subject,
            "approval_policy", policyId,
            input.expectedPolicyVersion() + 1, correlationId,
            Map.of(
                "policyVersionId", revisionId.toString(),
                "supersedesId", context.policyVersionId().toString()));
        return approvalPolicy(policyId);
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequestView> approvalRequests(
        String subject,
        UUID engagementId
    ) {
        TenantAuthorizationService.ProjectListScope scope =
            authorization.projectListScope(subject, engagementId);
        return jdbc.query("""
            SELECT id, project_id FROM core_approval_requests
            WHERE engagement_id = ?
            ORDER BY requested_at DESC, id
            """, (rs, row) -> new ScopedApprovalRequest(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class)), engagementId)
            .stream()
            .filter(request -> scope.allProjects()
                || (request.projectId() != null
                    && scope.projectIds().contains(request.projectId())))
            .map(request -> approvalRequest(request.id()))
            .toList();
    }

    @Transactional(readOnly = true)
    public ApprovalRequestView approvalRequest(
        String subject,
        UUID requestId
    ) {
        RequestContext context = requestContext(requestId, false);
        if (context.projectId() == null) {
            authorization.requireEngagement(subject, context.engagementId());
        } else {
            authorization.requireProject(subject, context.projectId());
        }
        return approvalRequest(requestId);
    }

    @Transactional
    public ApprovalRequestView createApprovalRequest(
        String subject,
        UUID engagementId,
        CreateApprovalRequestInput input,
        UUID correlationId
    ) {
        authorization.requireEngagementPermission(
            subject, engagementId, "approval.request.create");
        RequestPolicy policy = jdbc.query("""
            SELECT policy.id AS policy_id,
                   version.id AS policy_version_id,
                   policy.project_id, policy.action_type,
                   version.prohibit_self_approval
            FROM approval_policies policy
            JOIN approval_policy_versions version
              ON version.policy_id = policy.id
            WHERE policy.id = ?
              AND policy.engagement_id = ?
              AND policy.status = 'ACTIVE'
              AND version.status = 'PUBLISHED'
              AND version.valid_from <= CURRENT_DATE
              AND (version.valid_to IS NULL
                   OR version.valid_to >= CURRENT_DATE)
            ORDER BY version.valid_from DESC, version.version DESC
            LIMIT 1
            """, rs -> rs.next() ? new RequestPolicy(
                rs.getObject("policy_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("action_type"),
                rs.getBoolean("prohibit_self_approval")) : null,
            input.policyId(), engagementId);
        if (policy == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        if (policy.projectId() != null) {
            throw new IllegalArgumentException(
                "Governed month reopen policies must be engagement-scoped.");
        }
        ApprovalRequestView replay = jdbc.query("""
            SELECT id FROM core_approval_requests
            WHERE requested_by_subject = ? AND idempotency_key = ?
            """, rs -> rs.next()
                ? approvalRequest(rs.getObject("id", UUID.class)) : null,
            subject, input.idempotencyKey());
        if (replay != null) {
            if (!replay.policyId().equals(input.policyId())
                || !replay.engagementId().equals(engagementId)
                || !replay.objectId().equals(input.objectId())) {
                throw new DomainConflictException(
                    "APPROVAL_IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used for different approval content.");
            }
            return replay;
        }
        if (!"REOPEN".equals(policy.actionType())) {
            throw new IllegalArgumentException(
                "Generic approval requests currently support only governed engagement-month reopen.");
        }
        ResolvedApprovalObject resolved = resolveReopenObject(
            engagementId, input.objectId());
        UUID id = UUID.randomUUID();
        String requiredPermission = approvalPermission(policy.actionType());
        jdbc.update("""
            INSERT INTO core_approval_requests(
                id, policy_version_id, engagement_id, project_id,
                object_type, object_id, object_version, object_hash,
                required_permission_code, requested_by_subject,
                idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id, policy.policyVersionId(), engagementId, null,
            "ENGAGEMENT_MONTH", input.objectId(),
            resolved.version(), resolved.hash(), requiredPermission,
            subject, input.idempotencyKey());
        snapshotApprovalStages(
            id, policy.policyVersionId(), engagementId, null);
        audit(
            engagementId, null, "APPROVAL_REQUEST_CREATED", subject,
            "approval_request", id, 0L, correlationId,
            Map.of(
                "policyId", policy.policyId().toString(),
                "objectType", "ENGAGEMENT_MONTH",
                "objectId", input.objectId().toString(),
                "objectHash", resolved.hash()));
        return approvalRequest(id);
    }

    @Transactional
    public UUID createF04ReopenApproval(
        String subject,
        UUID reopenRequestId,
        UUID monthId,
        UUID correlationId
    ) {
        LegacyReopenContext legacy = jdbc.query("""
            SELECT month.engagement_id, reopen_request.requested_by_subject
            FROM month_reopen_requests reopen_request
            JOIN engagement_months month
              ON month.id = reopen_request.engagement_month_id
            WHERE reopen_request.id = ?
              AND reopen_request.engagement_month_id = ?
              AND reopen_request.status = 'REQUESTED'
              AND month.state = 'REOPEN_REQUESTED'
            """, rs -> rs.next() ? new LegacyReopenContext(
                rs.getObject("engagement_id", UUID.class),
                rs.getString("requested_by_subject")) : null,
            reopenRequestId, monthId);
        if (legacy == null || !legacy.requestedBySubject().equals(subject)) {
            throw new DomainConflictException(
                "F04_REOPEN_APPROVAL_BINDING_CONFLICT",
                "The legacy reopen request is not eligible for core approval.");
        }
        UUID replay = jdbc.query("""
            SELECT binding.core_approval_request_id
            FROM f04_core_reopen_approval_bindings binding
            WHERE binding.reopen_request_id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            reopenRequestId);
        if (replay != null) {
            return replay;
        }
        RequestPolicy policy = jdbc.query("""
            SELECT policy.id AS policy_id,
                   version.id AS policy_version_id,
                   policy.project_id, policy.action_type,
                   version.prohibit_self_approval
            FROM approval_policies policy
            JOIN approval_policy_versions version
              ON version.id = policy.current_version_id
            WHERE policy.engagement_id = ?
              AND policy.code = 'SYSTEM_F04_REOPEN'
              AND policy.status = 'ACTIVE'
              AND version.status = 'PUBLISHED'
              AND version.valid_from <= CURRENT_DATE
              AND (
                  version.valid_to IS NULL
                  OR version.valid_to >= CURRENT_DATE
              )
            """, rs -> rs.next() ? new RequestPolicy(
                rs.getObject("policy_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("action_type"),
                rs.getBoolean("prohibit_self_approval")) : null,
            legacy.engagementId());
        if (policy == null || !"REOPEN".equals(policy.actionType())) {
            throw new DomainConflictException(
                "F04_REOPEN_APPROVAL_POLICY_UNAVAILABLE",
                "The governed F04 reopen approval policy is unavailable.");
        }
        ResolvedApprovalObject resolved = resolveReopenObject(
            legacy.engagementId(), monthId);
        UUID approvalRequestId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO core_approval_requests(
                id, policy_version_id, engagement_id, project_id,
                object_type, object_id, object_version, object_hash,
                required_permission_code, requested_by_subject,
                idempotency_key)
            VALUES (?, ?, ?, NULL, 'ENGAGEMENT_MONTH', ?, ?, ?,
                    'month.transition', ?, ?)
            """,
            approvalRequestId, policy.policyVersionId(),
            legacy.engagementId(), monthId, resolved.version(),
            resolved.hash(), subject,
            "f04-reopen:" + reopenRequestId);
        snapshotApprovalStages(
            approvalRequestId, policy.policyVersionId(),
            legacy.engagementId(), null);
        jdbc.update("""
            INSERT INTO f04_core_reopen_approval_bindings(
                reopen_request_id, core_approval_request_id)
            VALUES (?, ?)
            """, reopenRequestId, approvalRequestId);
        audit(
            legacy.engagementId(), null,
            "F04_REOPEN_APPROVAL_REQUEST_CREATED", subject,
            "approval_request", approvalRequestId, 0L, correlationId,
            Map.of(
                "legacyReopenRequestId", reopenRequestId.toString(),
                "objectId", monthId.toString(),
                "objectHash", resolved.hash()));
        return approvalRequestId;
    }

    @Transactional
    public ApprovalRequestView actOnApprovalRequest(
        String subject,
        UUID requestId,
        ApprovalActionInput input,
        UUID correlationId
    ) {
        RequestContext request = requestContext(requestId, true);
        if (request.projectId() == null) {
            authorization.requireEngagementPermission(
                subject, request.engagementId(), "approval.request.act");
        } else {
            authorization.requireProjectPermission(
                subject, request.projectId(), "approval.request.act");
        }
        ApprovalActionReplay actionReplay = jdbc.query("""
            SELECT request_id, request_version, decision, reason,
                   delegation_id
            FROM core_approval_actions
            WHERE actor_subject = ? AND idempotency_key = ?
            """, rs -> rs.next() ? new ApprovalActionReplay(
                rs.getObject("request_id", UUID.class),
                rs.getLong("request_version"),
                rs.getString("decision"),
                rs.getString("reason"),
                rs.getObject("delegation_id", UUID.class)) : null,
            subject, input.idempotencyKey());
        if (actionReplay != null) {
            if (!actionReplay.requestId().equals(requestId)
                || actionReplay.requestVersion()
                    != input.expectedRequestVersion() + 1
                || !actionReplay.decision().equals(input.decision())
                || !java.util.Objects.equals(
                    actionReplay.reason(), blankToNull(input.reason()))
                || !java.util.Objects.equals(
                    actionReplay.delegationId(), input.delegationId())) {
                throw new DomainConflictException(
                    "APPROVAL_ACTION_IDEMPOTENCY_KEY_REUSED",
                    "The action idempotency key was already used for different content.",
                    request.version());
            }
            return approvalRequest(requestId);
        }
        requireVersion(
            "APPROVAL_REQUEST_VERSION_CONFLICT",
            request.version(),
            input.expectedRequestVersion());
        if (!"PENDING".equals(request.status())) {
            throw new DomainConflictException(
                "APPROVAL_REQUEST_NOT_PENDING",
                "Only a pending approval request can be acted on.",
                request.version());
        }
        if (request.evidenceRequired() && isBlank(input.reason())) {
            throw new IllegalArgumentException(
                "Approval evidence is required by the captured policy version.");
        }
        UUID actorId = jdbc.query("""
            SELECT id FROM user_profiles
            WHERE identity_subject = ? AND status = 'ACTIVE'
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            subject);
        if (actorId == null) {
            throw new AccessDeniedException(
                "The approval actor is unavailable.");
        }
        Boolean directEligible = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM core_approval_stage_snapshots
                WHERE request_id = ?
                  AND stage_order = ?
                  AND ? = ANY(eligible_user_ids)
            )
            """, Boolean.class,
            requestId, request.currentStageOrder(), actorId);
        UUID delegatedFrom = null;
        if (Boolean.TRUE.equals(directEligible)
            && input.delegationId() != null) {
            throw new IllegalArgumentException(
                "A directly eligible actor must not attach delegation evidence.");
        }
        if (!Boolean.TRUE.equals(directEligible)) {
            if (input.delegationId() == null) {
                throw new AccessDeniedException(
                    "The actor is not eligible for the current approval stage.");
            }
            delegatedFrom = jdbc.query("""
                SELECT delegator_user_id
                FROM delegations
                WHERE id = ?
                  AND engagement_id = ?
                  AND delegate_user_id = ?
                  AND status = 'ACTIVE'
                  AND CURRENT_TIMESTAMP >= valid_from
                  AND CURRENT_TIMESTAMP < valid_to
                  AND ? = ANY(action_codes)
                  AND EXISTS (
                      SELECT 1 FROM core_approval_stage_snapshots stage
                      WHERE stage.request_id = ?
                        AND stage.stage_order = ?
                        AND stage.allow_delegation
                  )
                """, rs -> rs.next()
                    ? rs.getObject(1, UUID.class) : null,
                input.delegationId(), request.engagementId(),
                actorId, request.requiredPermissionCode(),
                requestId, request.currentStageOrder());
            if (delegatedFrom == null) {
                throw new AccessDeniedException(
                    "The delegation is unavailable or expired.");
            }
            Boolean delegatorEligible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM core_approval_stage_snapshots
                    WHERE request_id = ?
                      AND stage_order = ?
                      AND ? = ANY(eligible_user_ids)
                )
                """, Boolean.class,
                requestId, request.currentStageOrder(), delegatedFrom);
            if (!Boolean.TRUE.equals(delegatorEligible)) {
                throw new AccessDeniedException(
                    "The delegation authority holder is not stage-eligible.");
            }
        }
        UUID authorityHolderId =
            delegatedFrom == null ? actorId : delegatedFrom;
        String authorityHolderSubject = delegatedFrom == null
            ? subject
            : jdbc.queryForObject("""
                SELECT identity_subject FROM user_profiles WHERE id = ?
                """, String.class, delegatedFrom);
        if (request.prohibitSelfApproval()
            && request.requestedBySubject().equals(
                authorityHolderSubject)) {
            throw new DomainConflictException(
                "APPROVAL_SELF_ACTION_PROHIBITED",
                "The request creator cannot act through direct or delegated authority.",
                request.version());
        }
        Boolean authorityAlreadyActed = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM core_approval_actions
                WHERE request_id = ?
                  AND stage_order = ?
                  AND COALESCE(delegated_from_user_id, actor_user_id) = ?
            )
            """, Boolean.class, requestId, request.currentStageOrder(),
            authorityHolderId);
        if (Boolean.TRUE.equals(authorityAlreadyActed)) {
            throw new DomainConflictException(
                "APPROVAL_AUTHORITY_ALREADY_ACTED",
                "This authority holder already acted on the current stage.",
                request.version());
        }
        UUID actionId = UUID.randomUUID();
        Map<String, Object> authoritySnapshot = new LinkedHashMap<>();
        authoritySnapshot.put("policyVersionId",
            request.policyVersionId().toString());
        authoritySnapshot.put("engagementId",
            request.engagementId().toString());
        authoritySnapshot.put("stageOrder", request.currentStageOrder());
        authoritySnapshot.put("requiredPermission",
            request.requiredPermissionCode());
        authoritySnapshot.put("actorSubject", subject);
        authoritySnapshot.put("delegationId",
            String.valueOf(input.delegationId()));
        authoritySnapshot.put("delegatedFromUserId",
            String.valueOf(delegatedFrom));
        jdbc.update("""
            INSERT INTO core_approval_actions(
                id, request_id, request_version, stage_order,
                decision, actor_user_id,
                actor_subject, authority_snapshot, delegated_from_user_id,
                delegation_id, idempotency_key, source, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'IN_APP', ?)
            """,
            actionId, requestId, request.version() + 1,
            request.currentStageOrder(), input.decision(), actorId,
            subject, json(authoritySnapshot),
            delegatedFrom, input.delegationId(),
            input.idempotencyKey(),
            blankToNull(input.reason()));

        String newStatus = "PENDING";
        int newStage = request.currentStageOrder();
        if (!"APPROVED".equals(input.decision())) {
            newStatus = input.decision();
        } else {
            StageProgress progress = jdbc.query("""
                SELECT stage.quorum_required,
                       count(DISTINCT COALESCE(
                           action.delegated_from_user_id,
                           action.actor_user_id))
                         FILTER (WHERE action.decision = 'APPROVED')
                           AS approvals,
                       EXISTS (
                           SELECT 1 FROM core_approval_stage_snapshots later
                           WHERE later.request_id = stage.request_id
                             AND later.stage_order > stage.stage_order
                       ) AS has_later
                FROM core_approval_stage_snapshots stage
                LEFT JOIN core_approval_actions action
                  ON action.request_id = ?
                 AND action.stage_order = stage.stage_order
                WHERE stage.request_id = ?
                  AND stage.stage_order = ?
                GROUP BY stage.request_id, stage.stage_order,
                         stage.quorum_required
                """, rs -> rs.next() ? new StageProgress(
                    rs.getInt("quorum_required"),
                    rs.getInt("approvals"),
                    rs.getBoolean("has_later")) : null,
                requestId, requestId,
                request.currentStageOrder());
            if (progress == null) {
                throw new IllegalStateException(
                    "Approval policy stage is unavailable.");
            }
            if (progress.approvals() >= progress.required()) {
                if (progress.hasLater()) {
                    Integer next = jdbc.queryForObject("""
                        SELECT min(stage_order)
                        FROM core_approval_stage_snapshots
                        WHERE request_id = ?
                          AND stage_order > ?
                        """, Integer.class,
                        requestId,
                        request.currentStageOrder());
                    newStage = next == null
                        ? request.currentStageOrder() : next;
                } else {
                    newStatus = "APPROVED";
                }
            }
        }
        int updated = jdbc.update("""
            UPDATE core_approval_requests
            SET status = ?, current_stage_order = ?, version = version + 1
            WHERE id = ? AND version = ? AND status = 'PENDING'
            """, newStatus, newStage, requestId, request.version());
        if (updated != 1) {
            throw new DomainConflictException(
                "APPROVAL_REQUEST_VERSION_CONFLICT",
                "The approval request changed before the action completed.",
                requestContext(requestId, false).version());
        }
        if ("APPROVED".equals(newStatus)) {
            if (!"ENGAGEMENT_MONTH".equals(request.objectType())) {
                throw new IllegalStateException(
                    "Unsupported approval object dispatch.");
            }
            jdbc.queryForObject(
                "SELECT set_config('vms.actor_subject', ?, true)",
                String.class, subject);
            jdbc.queryForObject(
                "SELECT set_config('vms.transition_reason', ?, true)",
                String.class, blankToNull(input.reason()) == null
                    ? "Approved governed reopen" : input.reason().trim());
            jdbc.queryForObject(
                "SELECT set_config('vms.correlation_id', ?, true)",
                String.class, correlationId.toString());
            int reopened = jdbc.update("""
                UPDATE engagement_months
                SET state = 'REOPENED',
                    governance_version = governance_version + 1
                WHERE id = ?
                  AND engagement_id = ?
                  AND state = 'REOPEN_REQUESTED'
                  AND governance_version = ?
                """, request.objectId(), request.engagementId(),
                request.objectVersion());
            if (reopened != 1) {
                throw new DomainConflictException(
                    "APPROVAL_OBJECT_VERSION_CONFLICT",
                    "The governed approval object changed before dispatch.",
                    request.version());
            }
        }
        audit(
            request.engagementId(), null, "APPROVAL_ACTION_RECORDED",
            subject, "approval_action", actionId, request.version() + 1,
            correlationId,
            Map.of(
                "requestId", requestId.toString(),
                "decision", input.decision(),
                "stageOrder", request.currentStageOrder(),
                "resultingStatus", newStatus));
        return approvalRequest(requestId);
    }

    @Transactional(readOnly = true)
    public List<DelegationView> delegations(
        String subject,
        UUID engagementId
    ) {
        authorization.requireEngagement(subject, engagementId);
        return jdbc.query("""
            SELECT id FROM delegations
            WHERE engagement_id = ?
            ORDER BY valid_from DESC, id
            """, (rs, row) -> delegation(
                rs.getObject("id", UUID.class)), engagementId);
    }

    @Transactional
    public DelegationView createDelegation(
        String subject,
        UUID engagementId,
        CreateDelegationInput input,
        UUID correlationId
    ) {
        authorization.requireEngagementPermission(
            subject, engagementId, "delegation.manage");
        if (!input.validTo().isAfter(input.validFrom())) {
            throw new IllegalArgumentException(
                "Delegation end must be after its start.");
        }
        String delegatorSubject = jdbc.query("""
            SELECT identity_subject FROM user_profiles WHERE id = ?
            """, rs -> rs.next() ? rs.getString(1) : null,
            input.delegatorUserId());
        if (delegatorSubject == null) {
            throw new IllegalArgumentException(
                "The delegation authority holder is unavailable.");
        }
        for (String action : new HashSet<>(input.actionCodes())) {
            Boolean authorityCoversWindow = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM memberships membership
                    JOIN user_profiles user_profile
                      ON user_profile.id = membership.user_profile_id
                    JOIN role_assignments assignment
                      ON assignment.user_profile_id = user_profile.id
                     AND assignment.organization_id =
                         membership.organization_id
                    JOIN roles role
                      ON role.id = assignment.role_id
                     AND role.status = 'ACTIVE'
                    JOIN role_permissions mapping
                      ON mapping.role_id = role.id
                    JOIN permissions permission
                      ON permission.id = mapping.permission_id
                    WHERE user_profile.identity_subject = ?
                      AND user_profile.status = 'ACTIVE'
                      AND membership.organization_id = ?
                      AND membership.status = 'ACTIVE'
                      AND membership.valid_from <= ?::timestamptz::date
                      AND (
                          membership.valid_to IS NULL
                          OR membership.valid_to >= ?::timestamptz::date
                      )
                      AND assignment.status = 'ACTIVE'
                      AND assignment.valid_from <= ?::timestamptz::date
                      AND (
                          assignment.valid_to IS NULL
                          OR assignment.valid_to >= ?::timestamptz::date
                      )
                      AND permission.code = ?
                      AND (
                          (
                              assignment.scope_type = 'ORGANIZATION'
                              AND assignment.scope_id = ?
                          )
                          OR (
                              assignment.scope_type = 'ENGAGEMENT'
                              AND assignment.scope_id = ?
                          )
                          OR (
                              assignment.scope_type = 'PROJECT'
                              AND assignment.scope_id = ?
                          )
                      )
                )
                """, Boolean.class,
                delegatorSubject, input.organizationId(),
                input.validFrom(), input.validTo(),
                input.validFrom(), input.validTo(), action,
                input.organizationId(), engagementId, input.projectId());
            if (!Boolean.TRUE.equals(authorityCoversWindow)) {
                throw new IllegalArgumentException(
                    "Delegation cannot exceed the authority holder's scope.");
            }
        }
        Boolean bothMembershipsCoverWindow = jdbc.queryForObject("""
            SELECT count(DISTINCT membership.user_profile_id) = 2
            FROM memberships membership
            JOIN user_profiles user_profile
              ON user_profile.id = membership.user_profile_id
            WHERE membership.organization_id = ?
              AND membership.user_profile_id IN (?, ?)
              AND user_profile.status = 'ACTIVE'
              AND membership.status = 'ACTIVE'
              AND membership.valid_from <= ?::timestamptz::date
              AND (
                  membership.valid_to IS NULL
                  OR membership.valid_to >= ?::timestamptz::date
              )
            """, Boolean.class,
            input.organizationId(), input.delegatorUserId(),
            input.delegateUserId(), input.validFrom(), input.validTo());
        if (!Boolean.TRUE.equals(bothMembershipsCoverWindow)) {
            throw new IllegalArgumentException(
                "Both delegation users need effective membership for the full window.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO delegations(
                id, organization_id, engagement_id, project_id,
                delegator_user_id, delegate_user_id, action_codes,
                valid_from, valid_to, reason, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id, input.organizationId(), engagementId, input.projectId(),
            input.delegatorUserId(), input.delegateUserId(),
            input.actionCodes().toArray(String[]::new),
            input.validFrom(), input.validTo(), input.reason().trim(), subject);
        audit(
            engagementId, input.organizationId(), "DELEGATION_CREATED",
            subject, "delegation", id, 0L, correlationId,
            Map.of(
                "delegatorUserId", input.delegatorUserId().toString(),
                "delegateUserId", input.delegateUserId().toString(),
                "actionCodes", input.actionCodes()));
        return delegation(id);
    }

    @Transactional
    public DelegationView revokeDelegation(
        String subject,
        UUID delegationId,
        RevokeDelegationInput input,
        UUID correlationId
    ) {
        DelegationView current = delegation(delegationId, true);
        authorization.requireEngagementPermission(
            subject, current.engagementId(), "delegation.manage");
        requireVersion(
            "DELEGATION_VERSION_CONFLICT",
            current.version(),
            input.expectedVersion());
        if (!"ACTIVE".equals(current.status())) {
            throw new DomainConflictException(
                "DELEGATION_NOT_ACTIVE",
                "Only an active delegation can be revoked.",
                current.version());
        }
        int updated = jdbc.update("""
            UPDATE delegations
            SET status = 'REVOKED', reason = ?,
                revoked_by_subject = ?, revoked_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = ? AND version = ? AND status = 'ACTIVE'
            """,
            input.reason().trim(), subject, delegationId,
            input.expectedVersion());
        if (updated != 1) {
            throw new DomainConflictException(
                "DELEGATION_VERSION_CONFLICT",
                "The delegation changed before it was revoked.",
                delegation(delegationId).version());
        }
        audit(
            current.engagementId(), current.organizationId(),
            "DELEGATION_REVOKED", subject, "delegation", delegationId,
            input.expectedVersion() + 1, correlationId,
            Map.of("reason", input.reason().trim()));
        return delegation(delegationId);
    }

    @Transactional(readOnly = true)
    public List<MonthTransitionView> monthTransitions(
        String subject,
        UUID monthId
    ) {
        MonthContext month = monthContext(monthId, false);
        authorization.requireEngagement(
            subject, month.engagementId());
        return jdbc.query("""
            SELECT id, engagement_month_id, from_state, to_state,
                   from_version, to_version, actor_subject, reason,
                   correlation_id, transitioned_at
            FROM engagement_month_transition_history
            WHERE engagement_month_id = ?
            ORDER BY transitioned_at, id
            """, (rs, row) -> new MonthTransitionView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getLong("from_version"),
                rs.getLong("to_version"),
                rs.getString("actor_subject"),
                rs.getString("reason"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("transitioned_at", OffsetDateTime.class)),
            monthId);
    }

    @Transactional
    public MonthTransitionView transitionMonth(
        String subject,
        UUID monthId,
        MonthTransitionInput input,
        UUID correlationId
    ) {
        MonthContext month = monthContext(monthId, true);
        authorization.requireEngagementPermission(
            subject, month.engagementId(), "month.transition");
        requireVersion(
            "MONTH_VERSION_CONFLICT",
            month.version(),
            input.expectedVersion());
        validateSafeTransition(month, input.targetState());
        UUID effectiveConfigurationId = null;
        if ("PLANNING".equals(input.targetState())) {
            effectiveConfigurationId = jdbc.query("""
                SELECT id
                FROM engagement_configuration_versions
                WHERE engagement_id = ?
                  AND status = 'PUBLISHED'
                  AND valid_from <= ?
                  AND (valid_to IS NULL OR valid_to >= ?)
                ORDER BY valid_from DESC, version DESC
                LIMIT 1
                """, rs -> rs.next()
                    ? rs.getObject("id", UUID.class) : null,
                month.engagementId(), month.monthStartDate(),
                month.monthStartDate());
            if (effectiveConfigurationId == null) {
                throw new DomainConflictException(
                    "MONTH_CONFIGURATION_NOT_EFFECTIVE",
                    "No published configuration governs the represented month.",
                    month.version());
            }
        }
        jdbc.queryForObject(
            "SELECT set_config('vms.actor_subject', ?, true)",
            String.class, subject);
        jdbc.queryForObject(
            "SELECT set_config('vms.transition_reason', ?, true)",
            String.class, input.reason().trim());
        jdbc.queryForObject(
            "SELECT set_config('vms.correlation_id', ?, true)",
            String.class, correlationId.toString());
        int updated = jdbc.update("""
            UPDATE engagement_months
            SET state = ?, governance_version = governance_version + 1,
                governance_configuration_version_id =
                    COALESCE(?, governance_configuration_version_id),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND governance_version = ?
            """, input.targetState(), effectiveConfigurationId,
            monthId, input.expectedVersion());
        if (updated != 1) {
            throw new DomainConflictException(
                "MONTH_VERSION_CONFLICT",
                "The engagement month changed before the transition.",
                monthContext(monthId, false).version());
        }
        MonthTransitionView result = jdbc.query("""
            SELECT id, engagement_month_id, from_state, to_state,
                   from_version, to_version, actor_subject, reason,
                   correlation_id, transitioned_at
            FROM engagement_month_transition_history
            WHERE engagement_month_id = ? AND to_version = ?
            """, rs -> rs.next() ? new MonthTransitionView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getLong("from_version"),
                rs.getLong("to_version"),
                rs.getString("actor_subject"),
                rs.getString("reason"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("transitioned_at", OffsetDateTime.class)) : null,
            monthId, input.expectedVersion() + 1);
        if (result == null) {
            throw new IllegalStateException(
                "Month transition history was not recorded.");
        }
        audit(
            month.engagementId(), null, "ENGAGEMENT_MONTH_TRANSITIONED",
            subject, "engagement_month", monthId,
            input.expectedVersion() + 1, correlationId,
            Map.of(
                "fromState", month.state(),
                "toState", input.targetState(),
                "reason", input.reason().trim()));
        return result;
    }

    private EngagementAdministrationView engagementRow(
        UUID id,
        boolean lock
    ) {
        EngagementAdministrationView result = jdbc.query("""
            SELECT id, engagement_code, name, status, default_project_id,
                   configuration_version_id, admin_version
            FROM engagements
            WHERE id = ?
            """ + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? new EngagementAdministrationView(
                rs.getObject("id", UUID.class),
                rs.getString("engagement_code"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getObject("default_project_id", UUID.class),
                rs.getObject("configuration_version_id", UUID.class),
                rs.getLong("admin_version")) : null,
            id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private ConfigurationView configuration(UUID id) {
        ConfigurationView result = jdbc.query("""
            SELECT id, engagement_id, version, status, valid_from, valid_to,
                   timezone, planning_due_day, certification_due_day,
                   confirmation_due_day, reopen_policy::text,
                   notification_policy::text, published_at
            FROM engagement_configuration_versions WHERE id = ?
            """, rs -> rs.next() ? configuration(rs) : null, id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private ConfigurationView configuration(ResultSet rs) throws SQLException {
        return new ConfigurationView(
            rs.getObject("id", UUID.class),
            rs.getObject("engagement_id", UUID.class),
            rs.getInt("version"),
            rs.getString("status"),
            rs.getObject("valid_from", LocalDate.class),
            rs.getObject("valid_to", LocalDate.class),
            rs.getString("timezone"),
            nullableInteger(rs, "planning_due_day"),
            nullableInteger(rs, "certification_due_day"),
            nullableInteger(rs, "confirmation_due_day"),
            map(rs.getString("reopen_policy")),
            map(rs.getString("notification_policy")),
            rs.getObject("published_at", OffsetDateTime.class));
    }

    private ContactGroupView contactGroup(UUID id) {
        ContactGroupView result = jdbc.query("""
            SELECT id, engagement_id, project_id, code, name, group_type,
                   status, version
            FROM contact_groups WHERE id = ?
            """, rs -> {
                if (!rs.next()) return null;
                return new ContactGroupView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("engagement_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("group_type"),
                    rs.getString("status"),
                    rs.getLong("version"),
                    contactMembers(id));
            }, id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private List<ContactMemberView> contactMembers(UUID groupId) {
        return jdbc.query("""
            SELECT id, user_profile_id, email, display_name,
                   role_attribution, verified, valid_from, valid_to, status
            FROM contact_group_members
            WHERE contact_group_id = ?
            ORDER BY display_name, id
            """, (rs, row) -> new ContactMemberView(
                rs.getObject("id", UUID.class),
                rs.getObject("user_profile_id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role_attribution"),
                rs.getBoolean("verified"),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class),
                rs.getString("status")),
            groupId);
    }

    private GroupContext groupContext(UUID id, boolean lock) {
        GroupContext result = jdbc.query("""
            SELECT id, engagement_id, version
            FROM contact_groups WHERE id = ?
            """ + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? new GroupContext(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getLong("version")) : null,
            id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private ApprovalPolicyView approvalPolicy(UUID id) {
        PolicyContext context = policyContext(id, false);
        return new ApprovalPolicyView(
            context.id(), context.engagementId(), context.projectId(),
            context.code(), context.name(), context.actionType(),
            context.status(), context.adminVersion(),
            context.policyVersionId(), context.policyVersion(),
            context.versionStatus(), context.validFrom(), context.validTo(),
            context.prohibitSelfApproval(), context.evidenceRequired(),
            context.rules(), approvalStages(context.policyVersionId()));
    }

    private PolicyContext policyContext(UUID id, boolean lock) {
        PolicyContext result = jdbc.query("""
            SELECT policy.id, policy.engagement_id, policy.project_id,
                   policy.code, policy.name, policy.action_type, policy.status,
                   policy.version AS admin_version,
                   version.id AS policy_version_id,
                   version.version AS policy_version,
                   version.status AS version_status,
                   version.valid_from, version.valid_to,
                   version.prohibit_self_approval,
                   version.evidence_required, version.rules::text
            FROM approval_policies policy
            JOIN approval_policy_versions version
              ON version.id = policy.current_version_id
            WHERE policy.id = ?
            """ + (lock ? " FOR UPDATE OF policy" : ""),
            rs -> rs.next() ? new PolicyContext(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("action_type"),
                rs.getString("status"),
                rs.getLong("admin_version"),
                rs.getObject("policy_version_id", UUID.class),
                rs.getInt("policy_version"),
                rs.getString("version_status"),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class),
                rs.getBoolean("prohibit_self_approval"),
                rs.getBoolean("evidence_required"),
                map(rs.getString("rules"))) : null,
            id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private List<ApprovalStageView> approvalStages(UUID versionId) {
        return jdbc.query("""
            SELECT id, stage_order, name, role_code, contact_group_id,
                   explicit_assignee_id, permission_code,
                   quorum_mode, quorum_required,
                   allow_delegation, due_duration_hours
            FROM approval_policy_stages
            WHERE policy_version_id = ?
            ORDER BY stage_order
            """, (rs, row) -> new ApprovalStageView(
                rs.getObject("id", UUID.class),
                rs.getInt("stage_order"),
                rs.getString("name"),
                rs.getString("role_code"),
                rs.getObject("contact_group_id", UUID.class),
                rs.getObject("explicit_assignee_id", UUID.class),
                rs.getString("permission_code"),
                rs.getString("quorum_mode"),
                rs.getInt("quorum_required"),
                rs.getBoolean("allow_delegation"),
                nullableInteger(rs, "due_duration_hours")),
            versionId);
    }

    private ApprovalRequestView approvalRequest(UUID id) {
        ApprovalRequestView result = jdbc.query("""
            SELECT request.id, policy.id AS policy_id,
                   request.policy_version_id, request.engagement_id,
                   request.project_id, request.object_type, request.object_id,
                   request.object_version, request.object_hash,
                   request.required_permission_code,
                   request.current_stage_order, request.status,
                   request.version, request.requested_by_subject,
                   request.requested_at,
                   policy_version.evidence_required
            FROM core_approval_requests request
            JOIN approval_policy_versions policy_version
              ON policy_version.id = request.policy_version_id
            JOIN approval_policies policy
              ON policy.id = policy_version.policy_id
            WHERE request.id = ?
            """, rs -> {
                if (!rs.next()) return null;
                UUID policyVersionId =
                    rs.getObject("policy_version_id", UUID.class);
                return new ApprovalRequestView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("policy_id", UUID.class),
                    policyVersionId,
                    rs.getObject("engagement_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("object_type"),
                    rs.getObject("object_id", UUID.class),
                    rs.getLong("object_version"),
                    rs.getString("object_hash"),
                    rs.getString("required_permission_code"),
                    rs.getInt("current_stage_order"),
                    rs.getString("status"),
                    rs.getLong("version"),
                    rs.getString("requested_by_subject"),
                    rs.getObject("requested_at", OffsetDateTime.class),
                    rs.getBoolean("evidence_required"),
                    approvalRequestStages(id),
                    approvalActions(id));
            }, id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private List<ApprovalActionView> approvalActions(UUID requestId) {
        return jdbc.query("""
            SELECT id, stage_order, decision, actor_user_id, actor_subject,
                   authority_snapshot::text, delegated_from_user_id,
                   delegation_id, source, reason, acted_at
            FROM core_approval_actions
            WHERE request_id = ?
            ORDER BY acted_at, id
            """, (rs, row) -> new ApprovalActionView(
                rs.getObject("id", UUID.class),
                rs.getInt("stage_order"),
                rs.getString("decision"),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_subject"),
                map(rs.getString("authority_snapshot")),
                rs.getObject("delegated_from_user_id", UUID.class),
                rs.getObject("delegation_id", UUID.class),
                rs.getString("source"),
                rs.getString("reason"),
                rs.getObject("acted_at", OffsetDateTime.class)),
            requestId);
    }

    private List<ApprovalStageView> approvalRequestStages(UUID requestId) {
        return jdbc.query("""
            SELECT stage.id, stage.stage_order, stage.name, stage.role_code,
                   snapshot.contact_group_id, stage.explicit_assignee_id,
                   stage.permission_code,
                   snapshot.quorum_mode, snapshot.quorum_required,
                   snapshot.allow_delegation, stage.due_duration_hours
            FROM core_approval_stage_snapshots snapshot
            JOIN approval_policy_stages stage
              ON stage.policy_version_id = snapshot.policy_version_id
             AND stage.stage_order = snapshot.stage_order
            WHERE snapshot.request_id = ?
            ORDER BY snapshot.stage_order
            """, (rs, row) -> new ApprovalStageView(
                rs.getObject("id", UUID.class),
                rs.getInt("stage_order"),
                rs.getString("name"),
                rs.getString("role_code"),
                rs.getObject("contact_group_id", UUID.class),
                rs.getObject("explicit_assignee_id", UUID.class),
                rs.getString("permission_code"),
                rs.getString("quorum_mode"),
                rs.getInt("quorum_required"),
                rs.getBoolean("allow_delegation"),
                nullableInteger(rs, "due_duration_hours")),
            requestId);
    }

    private RequestContext requestContext(UUID id, boolean lock) {
        RequestContext result = jdbc.query("""
            SELECT request.id, request.policy_version_id,
                   request.engagement_id, request.project_id,
                   request.object_type, request.object_id,
                   request.object_version, request.object_hash,
                   request.required_permission_code,
                   request.current_stage_order, request.status,
                   request.version, request.requested_by_subject,
                   policy_version.prohibit_self_approval,
                   policy_version.evidence_required
            FROM core_approval_requests request
            JOIN approval_policy_versions policy_version
              ON policy_version.id = request.policy_version_id
            WHERE request.id = ?
            """ + (lock ? " FOR UPDATE OF request" : ""),
            rs -> rs.next() ? new RequestContext(
                rs.getObject("id", UUID.class),
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("object_type"),
                rs.getObject("object_id", UUID.class),
                rs.getLong("object_version"),
                rs.getString("object_hash"),
                rs.getString("required_permission_code"),
                rs.getInt("current_stage_order"),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getString("requested_by_subject"),
                rs.getBoolean("prohibit_self_approval"),
                rs.getBoolean("evidence_required")) : null,
            id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private String approvalPermission(String actionType) {
        return switch (actionType) {
            case "PLAN_APPROVAL" -> "delivery.plan.approve";
            case "LEAVE_APPROVAL" -> "workforce.manage";
            case "REGULARIZATION", "ATTENDANCE_CORRECTION" ->
                "attendance.review";
            case "DELIVERY_CERTIFICATION" -> "certification.item.decide";
            case "MONTH_CONFIRMATION" ->
                "certification.confirmation.act";
            case "REOPEN" -> "month.transition";
            case "PROCUREMENT_EXCEPTION" -> "procurement.exception";
            default -> throw new IllegalArgumentException(
                "Unsupported approval policy action type.");
        };
    }

    private ResolvedApprovalObject resolveReopenObject(
        UUID engagementId,
        UUID monthId
    ) {
        ResolvedApprovalObject value = jdbc.query("""
            SELECT governance_version, state
            FROM engagement_months
            WHERE id = ? AND engagement_id = ?
            """, rs -> {
                if (!rs.next()) return null;
                long version = rs.getLong("governance_version");
                String state = rs.getString("state");
                return new ResolvedApprovalObject(
                    version, state,
                    approvalObjectHash(monthId, version, state));
            }, monthId, engagementId);
        if (value == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        if (!"REOPEN_REQUESTED".equals(value.state())) {
            throw new DomainConflictException(
                "APPROVAL_OBJECT_STATE_CONFLICT",
                "An engagement month must be reopen-requested before approval.",
                value.version());
        }
        return value;
    }

    private String approvalObjectHash(
        UUID objectId,
        long version,
        String state
    ) {
        String canonical = "ENGAGEMENT_MONTH:" + objectId + ":"
            + version + ":" + state;
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 is unavailable.", exception);
        }
    }

    private List<ApprovalStageInput> storedStageInputs(UUID versionId) {
        return approvalStages(versionId).stream()
            .map(stage -> new ApprovalStageInput(
                stage.name(), stage.roleCode(), stage.contactGroupId(),
                stage.explicitAssigneeId(), stage.permissionCode(),
                stage.quorumMode(),
                stage.quorumRequired(), stage.allowDelegation(),
                stage.dueDurationHours()))
            .toList();
    }

    private void insertStages(
        UUID versionId,
        List<ApprovalStageInput> stages
    ) {
        for (int index = 0; index < stages.size(); index++) {
            ApprovalStageInput stage = stages.get(index);
            jdbc.update("""
                INSERT INTO approval_policy_stages(
                    id, policy_version_id, stage_order, name, role_code,
                    contact_group_id, explicit_assignee_id, permission_code,
                    quorum_mode,
                    quorum_required, allow_delegation, due_duration_hours)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), versionId, index + 1, stage.name().trim(),
                blankToNull(stage.roleCode()), stage.contactGroupId(),
                stage.explicitAssigneeId(),
                blankToNull(stage.permissionCode()), stage.quorumMode(),
                stage.quorumRequired(), stage.allowDelegation(),
                stage.dueDurationHours());
        }
    }

    private void validateStages(
        UUID engagementId,
        UUID projectId,
        List<ApprovalStageInput> stages
    ) {
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException(
                "An approval policy requires at least one stage.");
        }
        for (ApprovalStageInput stage : stages) {
            int selectors = (isBlank(stage.roleCode()) ? 0 : 1)
                + (stage.contactGroupId() == null ? 0 : 1)
                + (stage.explicitAssigneeId() == null ? 0 : 1)
                + (isBlank(stage.permissionCode()) ? 0 : 1);
            if (selectors != 1) {
                throw new IllegalArgumentException(
                    "Each approval stage requires exactly one assignee source.");
            }
            if (stage.quorumRequired() <= 0
                || ("ANY_ONE".equals(stage.quorumMode())
                    && stage.quorumRequired() != 1)) {
                throw new IllegalArgumentException(
                    "Approval stage quorum is invalid.");
            }
            int eligibleCount = eligibleApproverCount(
                engagementId, projectId, stage);
            if (eligibleCount < stage.quorumRequired()) {
                throw new IllegalArgumentException(
                    "Approval stage quorum exceeds its eligible approvers.");
            }
            if ("ALL".equals(stage.quorumMode())
                && eligibleCount != stage.quorumRequired()) {
                throw new IllegalArgumentException(
                    "ALL quorum must equal the distinct eligible approver count.");
            }
        }
    }

    private int eligibleApproverCount(
        UUID engagementId,
        UUID projectId,
        ApprovalStageInput stage
    ) {
        return eligibleApproverIds(engagementId, projectId, stage).size();
    }

    private List<UUID> eligibleApproverIds(
        UUID engagementId,
        UUID projectId,
        ApprovalStageInput stage
    ) {
        if (stage.contactGroupId() != null) {
            return jdbc.query("""
                SELECT DISTINCT member.user_profile_id
                FROM contact_group_members member
                JOIN contact_groups contact_group
                  ON contact_group.id = member.contact_group_id
                WHERE contact_group.id = ?
                  AND contact_group.engagement_id = ?
                  AND contact_group.project_id IS NOT DISTINCT FROM ?
                  AND contact_group.status = 'ACTIVE'
                  AND member.status = 'ACTIVE'
                  AND member.verified
                  AND member.valid_from <= CURRENT_DATE
                  AND (member.valid_to IS NULL
                       OR member.valid_to >= CURRENT_DATE)
                ORDER BY member.user_profile_id
                """, (rs, row) -> rs.getObject(1, UUID.class),
                stage.contactGroupId(), engagementId, projectId);
        }
        if (stage.explicitAssigneeId() != null) {
            return jdbc.query("""
                SELECT DISTINCT user_profile.id
                FROM user_profiles user_profile
                JOIN memberships membership
                  ON membership.user_profile_id = user_profile.id
                JOIN engagements engagement ON engagement.id = ?
                WHERE user_profile.id = ?
                  AND user_profile.status = 'ACTIVE'
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= CURRENT_DATE
                  AND (
                      membership.valid_to IS NULL
                      OR membership.valid_to >= CURRENT_DATE)
                  AND membership.organization_id IN (
                      engagement.client_organization_id,
                      engagement.vendor_organization_id,
                      engagement.procurement_organization_id)
                """, (rs, row) -> rs.getObject(1, UUID.class),
                engagementId, stage.explicitAssigneeId());
        }
        if (!isBlank(stage.permissionCode())) {
            return jdbc.query("""
                SELECT DISTINCT assignment.user_profile_id
                FROM role_assignments assignment
                JOIN roles role
                  ON role.id = assignment.role_id
                 AND role.status = 'ACTIVE'
                JOIN role_permissions role_permission
                  ON role_permission.role_id = role.id
                JOIN permissions permission
                  ON permission.id = role_permission.permission_id
                 AND permission.code = ?
                JOIN memberships membership
                  ON membership.user_profile_id =
                     assignment.user_profile_id
                 AND membership.organization_id =
                     assignment.organization_id
                JOIN user_profiles user_profile
                  ON user_profile.id = assignment.user_profile_id
                JOIN engagements engagement ON engagement.id = ?
                WHERE user_profile.status = 'ACTIVE'
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= CURRENT_DATE
                  AND (
                      membership.valid_to IS NULL
                      OR membership.valid_to >= CURRENT_DATE
                  )
                  AND assignment.status = 'ACTIVE'
                  AND assignment.valid_from <= CURRENT_DATE
                  AND (
                      assignment.valid_to IS NULL
                      OR assignment.valid_to >= CURRENT_DATE
                  )
                  AND assignment.organization_id =
                      engagement.client_organization_id
                  AND (
                      assignment.scope_type = 'ORGANIZATION'
                      OR (
                          assignment.scope_type = 'ENGAGEMENT'
                          AND assignment.scope_id = engagement.id
                      )
                      OR (
                          assignment.scope_type = 'PROJECT'
                          AND ?::uuid IS NOT NULL
                          AND assignment.scope_id = ?::uuid
                      )
                  )
                ORDER BY assignment.user_profile_id
                """, (rs, row) -> rs.getObject(1, UUID.class),
                stage.permissionCode(), engagementId,
                projectId, projectId);
        }
        return jdbc.query("""
            SELECT DISTINCT assignment.user_profile_id
            FROM role_assignments assignment
            JOIN roles role ON role.id = assignment.role_id
            JOIN memberships membership
              ON membership.user_profile_id = assignment.user_profile_id
             AND membership.organization_id = assignment.organization_id
            JOIN user_profiles user_profile
              ON user_profile.id = assignment.user_profile_id
            JOIN engagements engagement ON engagement.id = ?
            WHERE role.code = ?
              AND role.status = 'ACTIVE'
              AND user_profile.status = 'ACTIVE'
              AND membership.status = 'ACTIVE'
              AND membership.valid_from <= CURRENT_DATE
              AND (
                  membership.valid_to IS NULL
                  OR membership.valid_to >= CURRENT_DATE
              )
              AND assignment.status = 'ACTIVE'
              AND assignment.valid_from <= CURRENT_DATE
              AND (assignment.valid_to IS NULL
                   OR assignment.valid_to >= CURRENT_DATE)
              AND assignment.organization_id IN (
                  engagement.client_organization_id,
                  engagement.vendor_organization_id,
                  engagement.procurement_organization_id)
              AND (
                  (assignment.scope_type = 'ORGANIZATION')
                  OR (assignment.scope_type = 'ENGAGEMENT'
                      AND assignment.scope_id = engagement.id)
                  OR (assignment.scope_type = 'PROJECT'
                      AND ? IS NOT NULL
                      AND assignment.scope_id = ?)
              )
            ORDER BY assignment.user_profile_id
            """, (rs, row) -> rs.getObject(1, UUID.class),
            engagementId, stage.roleCode(),
            projectId, projectId);
    }

    private void snapshotApprovalStages(
        UUID requestId,
        UUID policyVersionId,
        UUID engagementId,
        UUID projectId
    ) {
        List<ApprovalStageView> stages = approvalStages(policyVersionId);
        for (ApprovalStageView stage : stages) {
            ApprovalStageInput selector = new ApprovalStageInput(
                stage.name(), stage.roleCode(), stage.contactGroupId(),
                stage.explicitAssigneeId(), stage.permissionCode(),
                stage.quorumMode(),
                stage.quorumRequired(), stage.allowDelegation(),
                stage.dueDurationHours());
            List<UUID> eligible = eligibleApproverIds(
                engagementId, projectId, selector);
            int requestQuorum = "ALL".equals(stage.quorumMode())
                ? eligible.size() : stage.quorumRequired();
            if (eligible.isEmpty()
                || eligible.size() < requestQuorum) {
                throw new DomainConflictException(
                    "APPROVAL_ELIGIBILITY_CHANGED",
                    "Eligible approval authorities changed before request creation.");
            }
            Long groupVersion = stage.contactGroupId() == null
                ? null : jdbc.queryForObject("""
                    SELECT version FROM contact_groups WHERE id = ?
                    """, Long.class, stage.contactGroupId());
            jdbc.update("""
                INSERT INTO core_approval_stage_snapshots(
                    request_id, stage_order, policy_version_id,
                    contact_group_id, contact_group_version, quorum_mode,
                    quorum_required, allow_delegation, eligible_user_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::uuid[])
                """, requestId, stage.stageOrder(), policyVersionId,
                stage.contactGroupId(), groupVersion, stage.quorumMode(),
                requestQuorum, stage.allowDelegation(),
                eligible.toArray(UUID[]::new));
        }
    }

    private DelegationView delegation(UUID id) {
        return delegation(id, false);
    }

    private DelegationView delegation(UUID id, boolean lock) {
        DelegationView result = jdbc.query("""
            SELECT delegation.id, delegation.organization_id,
                   delegation.engagement_id, delegation.project_id,
                   delegation.delegator_user_id,
                   delegator.display_name AS delegator_name,
                   delegation.delegate_user_id,
                   delegate.display_name AS delegate_name,
                   delegation.action_codes, delegation.valid_from,
                   delegation.valid_to, delegation.status,
                   delegation.reason, delegation.version
            FROM delegations delegation
            JOIN user_profiles delegator
              ON delegator.id = delegation.delegator_user_id
            JOIN user_profiles delegate
              ON delegate.id = delegation.delegate_user_id
            WHERE delegation.id = ?
            """ + (lock ? " FOR UPDATE OF delegation" : ""),
            rs -> rs.next() ? new DelegationView(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("delegator_user_id", UUID.class),
                rs.getString("delegator_name"),
                rs.getObject("delegate_user_id", UUID.class),
                rs.getString("delegate_name"),
                stringArray(rs, "action_codes"),
                rs.getObject("valid_from", OffsetDateTime.class),
                rs.getObject("valid_to", OffsetDateTime.class),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getLong("version")) : null,
            id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private MonthContext monthContext(UUID id, boolean lock) {
        MonthContext result = jdbc.query("""
            SELECT month.id, month.engagement_id, month.month_start_date,
                   month.state, month.governance_version,
                   engagement.status AS engagement_status,
                   engagement.configuration_version_id
            FROM engagement_months month
            JOIN engagements engagement ON engagement.id = month.engagement_id
            WHERE month.id = ?
            """ + (lock ? " FOR UPDATE OF month" : ""),
            rs -> rs.next() ? new MonthContext(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("month_start_date", LocalDate.class),
                rs.getString("state"),
                rs.getLong("governance_version"),
                rs.getString("engagement_status"),
                rs.getObject("configuration_version_id", UUID.class)) : null,
            id);
        if (result == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return result;
    }

    private void validateSafeTransition(
        MonthContext month,
        String targetState
    ) {
        boolean allowed =
            ("DRAFT".equals(month.state()) && "PLANNING".equals(targetState))
                || ("PLAN_APPROVED".equals(month.state())
                    && "ACTIVE".equals(targetState))
                || ("REOPENED".equals(month.state())
                    && "PLANNING".equals(targetState))
                || (REOPEN_REQUEST_STATES.contains(month.state())
                    && "REOPEN_REQUESTED".equals(targetState));
        if (!allowed) {
            throw new DomainConflictException(
                "INVALID_MONTH_TRANSITION",
                "The requested month transition is not an administrative transition.",
                month.version());
        }
        if ("PLANNING".equals(targetState)
            && !"ACTIVE".equals(month.engagementStatus())) {
            throw new DomainConflictException(
                "MONTH_TRANSITION_PRECONDITION_FAILED",
                "Planning requires an active engagement.",
                month.version());
        }
        if ("ACTIVE".equals(targetState)
            && month.monthStartDate().isAfter(LocalDate.now(clock))) {
            throw new DomainConflictException(
                "MONTH_TRANSITION_PRECONDITION_FAILED",
                "A future month cannot be activated early through core administration.",
                month.version());
        }
    }

    private DomainConflictException staleEngagement(UUID engagementId) {
        return new DomainConflictException(
            "ENGAGEMENT_VERSION_CONFLICT",
            "The engagement changed before the update was applied.",
            engagementRow(engagementId, false).version());
    }

    private void audit(
        UUID engagementId,
        UUID organizationId,
        String eventType,
        String actor,
        String subjectType,
        UUID subjectId,
        Long version,
        UUID correlationId,
        Map<String, ?> payload
    ) {
        jdbc.update("""
            INSERT INTO core_audit_events(
                id, engagement_id, organization_id, event_type,
                actor_subject, subject_type, subject_id, subject_version,
                correlation_id, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """,
            UUID.randomUUID(), engagementId, organizationId, eventType,
            actor, subjectType, subjectId, version, correlationId,
            json(payload));
    }

    private void requireVersion(
        String code,
        long current,
        long expected
    ) {
        if (current != expected) {
            throw new DomainConflictException(
                code, "The supplied version is stale.", current);
        }
    }

    private void validateDateWindow(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException(
                "The effective end date cannot precede the start date.");
        }
    }

    private void validateDueDay(Integer value) {
        if (value != null && (value < 1 || value > 28)) {
            throw new IllegalArgumentException(
                "Configuration due days must be between 1 and 28.");
        }
    }

    private Integer nullableInteger(ResultSet rs, String column)
        throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private List<String> stringArray(ResultSet rs, String column)
        throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) return List.of();
        Object value = array.getArray();
        if (value instanceof String[] strings) {
            return List.of(strings);
        }
        Object[] objects = (Object[]) value;
        List<String> result = new ArrayList<>(objects.length);
        for (Object object : objects) {
            result.add(String.valueOf(object));
        }
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Unable to serialize core administration data.", exception);
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(
                value, new TypeReference<LinkedHashMap<String, Object>>() {
                });
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Unable to read core administration data.", exception);
        }
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record GroupContext(UUID id, UUID engagementId, long version) {
    }

    private record PolicyContext(
        UUID id,
        UUID engagementId,
        UUID projectId,
        String code,
        String name,
        String actionType,
        String status,
        long adminVersion,
        UUID policyVersionId,
        int policyVersion,
        String versionStatus,
        LocalDate validFrom,
        LocalDate validTo,
        boolean prohibitSelfApproval,
        boolean evidenceRequired,
        Map<String, Object> rules
    ) {
    }

    private record RequestPolicy(
        UUID policyId,
        UUID policyVersionId,
        UUID projectId,
        String actionType,
        boolean prohibitSelfApproval
    ) {
    }

    private record LegacyReopenContext(
        UUID engagementId,
        String requestedBySubject
    ) {
    }

    private record RequestContext(
        UUID id,
        UUID policyVersionId,
        UUID engagementId,
        UUID projectId,
        String objectType,
        UUID objectId,
        long objectVersion,
        String objectHash,
        String requiredPermissionCode,
        int currentStageOrder,
        String status,
        long version,
        String requestedBySubject,
        boolean prohibitSelfApproval,
        boolean evidenceRequired
    ) {
    }

    private record ResolvedApprovalObject(
        long version,
        String state,
        String hash
    ) {
    }

    private record ScopedApprovalRequest(UUID id, UUID projectId) {
    }

    private record StageProgress(
        int required,
        int approvals,
        boolean hasLater
    ) {
    }

    private record ApprovalActionReplay(
        UUID requestId,
        long requestVersion,
        String decision,
        String reason,
        UUID delegationId
    ) {
    }

    private record MonthContext(
        UUID id,
        UUID engagementId,
        LocalDate monthStartDate,
        String state,
        long version,
        String engagementStatus,
        UUID configurationVersionId
    ) {
    }
}

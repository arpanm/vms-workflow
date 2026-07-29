package com.vms.workflow.application;

import com.vms.workflow.api.ApiDtos.EngagementMonthView;
import com.vms.workflow.api.ApiDtos.EngagementView;
import com.vms.workflow.api.ApiDtos.MeView;
import com.vms.workflow.api.ApiDtos.OrganizationView;
import com.vms.workflow.api.ApiDtos.ProjectView;
import com.vms.workflow.domain.EngagementMonth;
import com.vms.workflow.domain.Membership;
import com.vms.workflow.domain.Project;
import com.vms.workflow.infrastructure.EngagementMonthRepository;
import com.vms.workflow.infrastructure.EngagementRepository;
import com.vms.workflow.infrastructure.OrganizationRepository;
import com.vms.workflow.infrastructure.ProjectRepository;
import com.vms.workflow.infrastructure.UserProfileRepository;
import com.vms.workflow.security.TenantAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CatalogQueryService {
    private final UserProfileRepository users;
    private final OrganizationRepository organizations;
    private final EngagementRepository engagements;
    private final ProjectRepository projects;
    private final EngagementMonthRepository months;
    private final TenantAuthorizationService authorization;

    public CatalogQueryService(UserProfileRepository users, OrganizationRepository organizations,
                               EngagementRepository engagements, ProjectRepository projects,
                               EngagementMonthRepository months, TenantAuthorizationService authorization) {
        this.users = users;
        this.organizations = organizations;
        this.engagements = engagements;
        this.projects = projects;
        this.months = months;
        this.authorization = authorization;
    }

    public MeView me(String subject) {
        authorization.requireActivePrincipal(subject);
        var profile = users.findByIdentitySubjectAndStatus(subject, "ACTIVE")
            .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                "The authenticated identity has no active application access."));
        return MeView.from(
            profile,
            authorization.sessionMemberships(subject),
            authorization.effectivePermissions(subject));
    }

    public List<OrganizationView> organizations(String subject) {
        return authorization.organizationMemberships(subject).stream()
            .map(Membership::getOrganization)
            .distinct()
            .map(OrganizationView::from)
            .toList();
    }

    public OrganizationView organization(String subject, UUID id) {
        authorization.requireOrganization(subject, id);
        return OrganizationView.from(organizations.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Organization not found.")));
    }

    public List<EngagementView> engagements(String subject, UUID organizationId) {
        authorization.requireOrganization(subject, organizationId);
        return engagements.findForOrganization(organizationId).stream().map(EngagementView::from).toList();
    }

    public EngagementView engagement(String subject, UUID id) {
        authorization.requireEngagement(subject, id);
        return EngagementView.from(engagements.findDetailedById(id)
            .orElseThrow(this::notFound));
    }

    public List<ProjectView> projects(String subject, UUID engagementId) {
        var scope = authorization.projectListScope(subject, engagementId);
        List<Project> readableProjects = scope.allProjects()
            ? projects.findByEngagementIdOrderByProjectCode(engagementId)
            : projects.findByEngagementIdAndIdInOrderByProjectCode(engagementId, scope.projectIds());
        return readableProjects.stream().map(ProjectView::from).toList();
    }

    public ProjectView project(String subject, UUID id) {
        authorization.requireProject(subject, id);
        Project project = projects.findById(id)
            .orElseThrow(this::notFound);
        return ProjectView.from(project);
    }

    public List<EngagementMonthView> months(String subject, UUID engagementId) {
        authorization.requireEngagement(subject, engagementId);
        return months.findByEngagementIdOrderByMonthStartDateDesc(engagementId).stream()
            .map(EngagementMonthView::from).toList();
    }

    public EngagementMonthView month(String subject, UUID id) {
        EngagementMonth month = months.findById(id)
            .orElseThrow(this::notFound);
        authorization.requireEngagement(subject, month.getEngagement().getId());
        return EngagementMonthView.from(month);
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }
}

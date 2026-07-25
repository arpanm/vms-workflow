package com.vms.workflow.api;

import com.vms.workflow.domain.Engagement;
import com.vms.workflow.domain.EngagementMonth;
import com.vms.workflow.domain.Membership;
import com.vms.workflow.domain.Organization;
import com.vms.workflow.domain.Project;
import com.vms.workflow.domain.UserProfile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record OrganizationView(UUID id, String code, String displayName, String legalName,
                                   String organizationType, String status, String defaultTimezone,
                                   String defaultLocale) {
        public static OrganizationView from(Organization value) {
            return new OrganizationView(value.getId(), value.getCode(), value.getDisplayName(),
                value.getLegalName(), value.getOrganizationType(), value.getStatus(),
                value.getDefaultTimezone(), value.getDefaultLocale());
        }
    }

    public record MembershipView(UUID organizationId, String organizationCode, String organizationName,
                                 String roleCode, LocalDate validFrom, LocalDate validTo) {
        public static MembershipView from(Membership value) {
            return new MembershipView(value.getOrganization().getId(), value.getOrganization().getCode(),
                value.getOrganization().getDisplayName(), value.getRoleCode(), value.getValidFrom(), value.getValidTo());
        }
    }

    public record MeView(UUID id, String subject, String email, String displayName,
                         List<MembershipView> memberships) {
        public static MeView from(UserProfile profile, List<Membership> memberships) {
            return new MeView(profile.getId(), profile.getIdentitySubject(), profile.getEmail(),
                profile.getDisplayName(), memberships.stream().map(MembershipView::from).toList());
        }
    }

    public record EngagementView(UUID id, String engagementCode, String name, UUID clientOrganizationId,
                                 UUID vendorOrganizationId, UUID procurementOrganizationId,
                                 String engagementModel, LocalDate startDate, LocalDate endDate, String status) {
        public static EngagementView from(Engagement value) {
            return new EngagementView(value.getId(), value.getEngagementCode(), value.getName(),
                value.getClientOrganization().getId(), value.getVendorOrganization().getId(),
                value.getProcurementOrganization() == null ? null : value.getProcurementOrganization().getId(),
                value.getEngagementModel(), value.getStartDate(), value.getEndDate(), value.getStatus());
        }
    }

    public record ProjectView(UUID id, UUID engagementId, String projectCode, String name,
                              String description, LocalDate startDate, LocalDate endDate, String status) {
        public static ProjectView from(Project value) {
            return new ProjectView(value.getId(), value.getEngagement().getId(), value.getProjectCode(),
                value.getName(), value.getDescription(), value.getStartDate(), value.getEndDate(), value.getStatus());
        }
    }

    public record EngagementMonthView(UUID id, UUID engagementId, LocalDate monthStartDate,
                                      String state, String riskStatus, boolean historicalFlag) {
        public static EngagementMonthView from(EngagementMonth value) {
            return new EngagementMonthView(value.getId(), value.getEngagement().getId(),
                value.getMonthStartDate(), value.getState(), value.getRiskStatus(), value.isHistoricalFlag());
        }
    }
}

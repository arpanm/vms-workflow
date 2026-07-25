package com.vms.workflow.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "memberships")
public class Membership {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_profile_id")
    private UserProfile userProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    private String roleCode;
    private String status;
    private LocalDate validFrom;
    private LocalDate validTo;

    protected Membership() {
    }

    public UUID getId() { return id; }
    public UserProfile getUserProfile() { return userProfile; }
    public Organization getOrganization() { return organization; }
    public String getRoleCode() { return roleCode; }
    public String getStatus() { return status; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
}

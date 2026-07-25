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
@Table(name = "engagements")
public class Engagement {
    @Id
    private UUID id;
    private String engagementCode;
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_organization_id")
    private Organization clientOrganization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_organization_id")
    private Organization vendorOrganization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "procurement_organization_id")
    private Organization procurementOrganization;

    private String engagementModel;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;

    protected Engagement() {
    }

    public UUID getId() { return id; }
    public String getEngagementCode() { return engagementCode; }
    public String getName() { return name; }
    public Organization getClientOrganization() { return clientOrganization; }
    public Organization getVendorOrganization() { return vendorOrganization; }
    public Organization getProcurementOrganization() { return procurementOrganization; }
    public String getEngagementModel() { return engagementModel; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getStatus() { return status; }
}

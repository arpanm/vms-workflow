package com.vms.workflow.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization {
    @Id
    private UUID id;
    private String code;
    private String legalName;
    private String displayName;
    private String organizationType;
    private String status;
    private String defaultTimezone;
    private String defaultLocale;

    protected Organization() {
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getLegalName() { return legalName; }
    public String getDisplayName() { return displayName; }
    public String getOrganizationType() { return organizationType; }
    public String getStatus() { return status; }
    public String getDefaultTimezone() { return defaultTimezone; }
    public String getDefaultLocale() { return defaultLocale; }
}

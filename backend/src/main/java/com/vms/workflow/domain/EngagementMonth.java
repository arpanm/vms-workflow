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
@Table(name = "engagement_months")
public class EngagementMonth {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "engagement_id")
    private Engagement engagement;

    private LocalDate monthStartDate;
    private String state;
    private String riskStatus;
    private boolean historicalFlag;
    private long governanceVersion;

    protected EngagementMonth() {
    }

    public UUID getId() { return id; }
    public Engagement getEngagement() { return engagement; }
    public LocalDate getMonthStartDate() { return monthStartDate; }
    public String getState() { return state; }
    public String getRiskStatus() { return riskStatus; }
    public boolean isHistoricalFlag() { return historicalFlag; }
    public long getGovernanceVersion() { return governanceVersion; }
}

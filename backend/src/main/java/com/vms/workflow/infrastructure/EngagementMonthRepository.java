package com.vms.workflow.infrastructure;

import com.vms.workflow.domain.EngagementMonth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EngagementMonthRepository extends JpaRepository<EngagementMonth, UUID> {
    List<EngagementMonth> findByEngagementIdOrderByMonthStartDateDesc(UUID engagementId);
}

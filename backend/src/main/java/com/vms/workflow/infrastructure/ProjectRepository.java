package com.vms.workflow.infrastructure;

import com.vms.workflow.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByEngagementIdOrderByProjectCode(UUID engagementId);

    List<Project> findByEngagementIdAndIdInOrderByProjectCode(UUID engagementId, Collection<UUID> ids);
}

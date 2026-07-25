package com.vms.workflow.infrastructure;

import com.vms.workflow.domain.Engagement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EngagementRepository extends JpaRepository<Engagement, UUID> {
    @Query("""
        select e from Engagement e
        join fetch e.clientOrganization
        join fetch e.vendorOrganization
        left join fetch e.procurementOrganization
        where e.clientOrganization.id = :organizationId
           or e.vendorOrganization.id = :organizationId
           or e.procurementOrganization.id = :organizationId
        order by e.engagementCode
        """)
    List<Engagement> findForOrganization(@Param("organizationId") UUID organizationId);

    @Query("""
        select e from Engagement e
        join fetch e.clientOrganization
        join fetch e.vendorOrganization
        left join fetch e.procurementOrganization
        where e.id = :id
        """)
    java.util.Optional<Engagement> findDetailedById(@Param("id") UUID id);
}

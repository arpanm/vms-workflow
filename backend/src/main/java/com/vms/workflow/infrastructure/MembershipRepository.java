package com.vms.workflow.infrastructure;

import com.vms.workflow.domain.Membership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    @Query("""
        select m from Membership m
        join fetch m.organization
        join m.userProfile u
        where u.identitySubject = :subject
          and u.status = 'ACTIVE'
          and m.organization.status = 'ACTIVE'
          and m.status = 'ACTIVE'
          and m.validFrom <= :today
          and (m.validTo is null or m.validTo >= :today)
        order by m.organization.displayName, m.roleCode
        """)
    List<Membership> findActiveForSubject(@Param("subject") String subject, @Param("today") LocalDate today);

}

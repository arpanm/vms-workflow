package com.vms.workflow.infrastructure;

import com.vms.workflow.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
    Optional<UserProfile> findByIdentitySubjectAndStatus(String identitySubject, String status);
}

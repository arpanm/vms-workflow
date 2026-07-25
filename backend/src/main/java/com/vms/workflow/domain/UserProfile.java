package com.vms.workflow.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "user_profiles")
public class UserProfile {
    @Id
    private UUID id;
    private String identitySubject;
    private String email;
    private String displayName;
    private String status;

    protected UserProfile() {
    }

    public UUID getId() { return id; }
    public String getIdentitySubject() { return identitySubject; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
}

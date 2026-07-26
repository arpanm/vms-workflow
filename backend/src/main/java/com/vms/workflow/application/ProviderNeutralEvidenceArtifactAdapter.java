package com.vms.workflow.application;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProviderNeutralEvidenceArtifactAdapter
    implements EvidenceArtifactAdapter {
    private final CertificationConfiguration configuration;

    public ProviderNeutralEvidenceArtifactAdapter(
        CertificationConfiguration configuration
    ) {
        this.configuration = configuration;
    }

    @Override
    public String configurationStatus() {
        return configuration.objectStorageProviderStatus();
    }

    @Override
    public ArtifactAccess resolve(UUID artifactId, String actorSubject) {
        return new ArtifactAccess(
            false, "ACTION_REQUIRED", null, "OBJECT_STORAGE_NOT_CONFIGURED");
    }
}

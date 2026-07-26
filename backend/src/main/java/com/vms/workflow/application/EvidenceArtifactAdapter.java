package com.vms.workflow.application;

import java.util.UUID;

public interface EvidenceArtifactAdapter {
    String configurationStatus();

    ArtifactAccess resolve(UUID artifactId, String actorSubject);

    record ArtifactAccess(
        boolean permitted,
        String status,
        String shortLivedUrl,
        String failureCode
    ) {
    }
}

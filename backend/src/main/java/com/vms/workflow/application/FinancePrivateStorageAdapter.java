package com.vms.workflow.application;

import java.util.UUID;

/**
 * Provider-neutral boundary for private F05 artifacts.
 *
 * <p>The API never returns provider keys or credentials. Implementations must
 * preserve the exact bytes under the supplied immutable artifact identifier.</p>
 */
public interface FinancePrivateStorageAdapter {
    String configurationStatus();

    void store(UUID artifactId, byte[] content);

    byte[] read(UUID artifactId);
}

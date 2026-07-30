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

    /**
     * Removes private bytes after the caller has recorded its governed,
     * same-transaction retention transition. Implementations that cannot make
     * deletion atomic with the metadata transaction must return {@code false}
     * from {@link #transactionalDeleteSupported()} and use a durable
     * provider-specific pending/retry workflow instead.
     */
    void delete(UUID artifactId);

    default boolean transactionalDeleteSupported() {
        return false;
    }
}

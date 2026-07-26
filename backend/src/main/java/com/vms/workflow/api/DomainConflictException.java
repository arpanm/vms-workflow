package com.vms.workflow.api;

public class DomainConflictException extends RuntimeException {
    private final String code;
    private final Long currentVersion;

    public DomainConflictException(String message) {
        this("DOMAIN_CONFLICT", message, null);
    }

    public DomainConflictException(String code, String message) {
        this(code, message, null);
    }

    public DomainConflictException(String code, String message, Long currentVersion) {
        super(message);
        this.code = code;
        this.currentVersion = currentVersion;
    }

    public String getCode() {
        return code;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }
}

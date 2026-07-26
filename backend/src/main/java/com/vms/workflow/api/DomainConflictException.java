package com.vms.workflow.api;

public class DomainConflictException extends RuntimeException {
    public DomainConflictException(String message) {
        super(message);
    }
}

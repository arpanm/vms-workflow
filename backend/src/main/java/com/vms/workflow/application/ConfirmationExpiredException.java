package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;

final class ConfirmationExpiredException extends DomainConflictException {
    ConfirmationExpiredException(long currentVersion) {
        super(
            "CONFIRMATION_EXPIRED",
            "The confirmation request due time has passed.",
            currentVersion
        );
    }
}

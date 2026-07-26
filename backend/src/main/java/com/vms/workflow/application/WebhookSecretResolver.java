package com.vms.workflow.application;

import java.util.List;

public interface WebhookSecretResolver {
    SecretKeys resolve(String secretReference);

    record SecretKeys(byte[] current, List<byte[]> previous) {
        public SecretKeys {
            current = current == null ? new byte[0] : current.clone();
            previous = previous == null
                ? List.of()
                : previous.stream().map(byte[]::clone).toList();
        }

        @Override
        public byte[] current() {
            return current.clone();
        }

        @Override
        public List<byte[]> previous() {
            return previous.stream().map(byte[]::clone).toList();
        }
    }
}

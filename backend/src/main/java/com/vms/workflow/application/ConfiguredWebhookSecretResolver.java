package com.vms.workflow.application;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class ConfiguredWebhookSecretResolver implements WebhookSecretResolver {
    private final Map<String, ConfiguredKeys> keysByReference;

    public ConfiguredWebhookSecretResolver(
        ObjectMapper objectMapper,
        @Value("${vms.linear.webhook-secret-set:{}}") String configuredKeys
    ) {
        try {
            keysByReference = objectMapper.readValue(configuredKeys, new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "vms.linear.webhook-secret-set must be a valid secret-reference map.",
                exception);
        }
    }

    @Override
    public SecretKeys resolve(String secretReference) {
        ConfiguredKeys configured = keysByReference.get(secretReference);
        if (configured == null || configured.current() == null
            || configured.current().isBlank()) {
            return new SecretKeys(new byte[0], List.of());
        }
        return new SecretKeys(
            configured.current().getBytes(StandardCharsets.UTF_8),
            configured.previous() == null
                ? List.of()
                : configured.previous().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.getBytes(StandardCharsets.UTF_8))
                    .toList());
    }

    private record ConfiguredKeys(String current, List<String> previous) {
    }
}

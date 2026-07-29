package com.vms.workflow.integration;

import com.vms.workflow.application.DeliveryCommitmentEmailAdapter;
import com.vms.workflow.application.DeliveryCommitmentOperationsWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:"
        + "///vms_workflow_delivery_commitment_worker",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.delivery.commitment.provider-status=CONFIGURED",
    "vms.delivery.commitment.worker-enabled=true",
    "vms.delivery.commitment.worker-initial-delay=PT1H",
    "vms.delivery.commitment.retry-delay=PT0.001S"
})
@Import(DeliveryCommitmentOperationsWorkerIT.AdapterConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class DeliveryCommitmentOperationsWorkerIT {
    @Autowired
    private DeliveryCommitmentOperationsWorker worker;

    @Autowired
    private StubCommitmentAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void resetAdapter() {
        adapter.messages.clear();
        adapter.result.set(new DeliveryCommitmentEmailAdapter.SendResult(
            "SENT", "provider-commitment", "provider-thread", null, false));
    }

    @Test
    void configuredCommitmentDispatchPreservesRecipientsAndIsReplaySafe()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);

        assertEquals(1, worker.dispatchCommitments());
        assertEquals(1, adapter.messages.size());
        DeliveryCommitmentEmailAdapter.OutboundCommitment message =
            adapter.messages.getFirst();
        assertEquals(baseline.planVersionId(), message.planVersionId());
        assertEquals(baseline.baselineId(), message.baselineId());
        JsonNode recipients = mapper.readTree(message.recipientSnapshotJson());
        assertEquals("shared@example.test",
            recipients.path("arrowFoundry").get(0).asText());
        assertEquals("owner@example.test",
            recipients.path("relianceStakeholders").get(0).asText());
        assertEquals("shared@example.test",
            recipients.path("procurementCc").get(0).asText());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM commitment_outbox
            WHERE id = ? AND status = 'SENT'
              AND provider_message_id = 'provider-commitment'
              AND provider_thread_id = 'provider-thread'
              AND sent_at IS NOT NULL
              AND subject_text LIKE '%[' || substring(? from 1 for 12) || ']%'
              AND plain_text LIKE '%' || ? || '%'
            """, Integer.class, message.outboxId(),
            baseline.checksum(), baseline.checksum()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM commitment_outbox_attempts
            WHERE outbox_id = ? AND attempt_number = 1 AND status = 'SENT'
            """, Integer.class, message.outboxId()));

        assertEquals(0, worker.dispatchCommitments());
        assertEquals(1, adapter.messages.size());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM commitment_outbox WHERE plan_version_id = ?
            """, Integer.class, baseline.planVersionId()));
        assertThrows(Exception.class, () -> jdbc.update("""
            UPDATE commitment_outbox SET subject_text = 'tampered' WHERE id = ?
            """, message.outboxId()));
    }

    @Test
    void retryRetainsOneOutboxAndOneSuccessfulProviderEffect()
        throws Exception {
        F04TestSupport.FrozenBaseline baseline =
            F04TestSupport.frozenBaseline(mvc, mapper);
        adapter.result.set(new DeliveryCommitmentEmailAdapter.SendResult(
            "FAILED", null, null, "TEMPORARY_PROVIDER_FAILURE", true));

        assertEquals(1, worker.dispatchCommitments());
        DeliveryCommitmentEmailAdapter.OutboundCommitment first =
            adapter.messages.getFirst();
        jdbc.update("""
            UPDATE commitment_outbox SET next_attempt_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, first.outboxId());
        adapter.result.set(new DeliveryCommitmentEmailAdapter.SendResult(
            "SENT", "provider-commitment", "provider-thread", null, false));

        assertEquals(1, worker.dispatchCommitments());
        assertEquals(2, adapter.messages.size());
        assertEquals(first.outboxId(), adapter.messages.get(1).outboxId());
        assertEquals(first.idempotencyKey(),
            adapter.messages.get(1).idempotencyKey());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM commitment_outbox
            WHERE plan_version_id = ? AND status = 'SENT'
              AND attempt_count = 2
            """, Integer.class, baseline.planVersionId()));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM commitment_outbox_attempts
            WHERE outbox_id = ?
            """, Integer.class, first.outboxId()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM commitment_outbox_attempts
            WHERE outbox_id = ? AND status = 'SENT'
            """, Integer.class, first.outboxId()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdapterConfiguration {
        @Bean
        @Primary
        StubCommitmentAdapter stubCommitmentAdapter() {
            return new StubCommitmentAdapter();
        }
    }

    static final class StubCommitmentAdapter
        implements DeliveryCommitmentEmailAdapter {
        private final AtomicReference<SendResult> result =
            new AtomicReference<>();
        private final List<OutboundCommitment> messages =
            new CopyOnWriteArrayList<>();

        @Override
        public String configurationStatus() {
            return "CONFIGURED";
        }

        @Override
        public SendResult send(OutboundCommitment commitment) {
            messages.add(commitment);
            return result.get();
        }
    }
}

package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.ENGAGEMENT;
import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.finance.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Transactional
class FinancePaginationIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void controlTowerKeysetMaintainsSnapshotAcrossInsertAndUpdate()
        throws Exception {
        Set<UUID> seeded = new HashSet<>();
        for (int index = 0; index < 55; index++) {
            UUID monthId = UUID.nameUUIDFromBytes(
                ("f05-pagination-" + index)
                    .getBytes(StandardCharsets.UTF_8));
            seeded.add(monthId);
            jdbc.update("""
                INSERT INTO engagement_months(
                    id, engagement_id, month_start_date, state,
                    risk_status, created_at, updated_at
                ) VALUES (?, ?::uuid, ?, 'DRAFT', 'ON_TRACK',
                          '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')
                ON CONFLICT DO NOTHING
                """, monthId, ENGAGEMENT,
                LocalDate.of(2010, 1, 1).plusMonths(index));
        }

        JsonNode first = mapper.readTree(mvc.perform(get(
                    "/api/v1/finance/procurement/control-tower")
                .with(token("user-procurement")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        JsonNode firstRows = first.path("rows");
        assertEquals(50, firstRows.path("items").size());
        assertEquals("SNAPSHOT_MEMBERSHIP_VALUES_LIVE_AT_READ",
            first.path("temporalMode").asText());
        assertEquals("LIVE_AT_READ", first.path("freshness").asText());
        String cursor = firstRows.path("nextCursor").asText();
        assertFalse(cursor.isBlank());

        UUID insertedAfterSnapshot = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO engagement_months(
                id, engagement_id, month_start_date, state,
                risk_status, created_at, updated_at
            ) VALUES (?, ?::uuid, '2030-01-01', 'DRAFT', 'ON_TRACK',
                      CURRENT_TIMESTAMP + INTERVAL '1 hour',
                      CURRENT_TIMESTAMP + INTERVAL '1 hour')
            """, insertedAfterSnapshot, ENGAGEMENT);
        UUID updatedMonth = UUID.fromString(
            firstRows.path("items").get(0).path("monthId").asText());
        jdbc.update("""
            UPDATE engagement_months
            SET risk_status = 'AT_RISK', updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, updatedMonth);
        UUID liveValueMonth = UUID.nameUUIDFromBytes(
            "f05-pagination-0".getBytes(StandardCharsets.UTF_8));
        UUID liveInvoice = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO invoices(
                id, engagement_month_id, vendor_organization_id,
                invoice_type, invoice_number, normalized_invoice_number,
                invoice_date, billing_period_start, billing_period_end,
                currency, status, current_version, optimistic_version,
                created_by_subject, correlation_id
            ) VALUES (?, ?, '00000000-0000-0000-0000-000000000101',
                      'PRIMARY', 'LIVE PAGE VALUE', 'live page value',
                      '2010-01-31', '2010-01-01', '2010-01-31',
                      'INR', 'DRAFT', 1, 1, 'user-arrow', ?)
            """, liveInvoice, liveValueMonth, UUID.randomUUID());

        JsonNode second = mapper.readTree(mvc.perform(get(
                    "/api/v1/finance/procurement/control-tower")
                .queryParam("cursor", cursor)
                .with(token("user-procurement")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

        Set<String> firstIds = ids(firstRows.path("items"));
        Set<String> secondIds = ids(second.path("rows").path("items"));
        assertTrue(firstIds.stream().noneMatch(secondIds::contains));
        assertFalse(firstIds.contains(insertedAfterSnapshot.toString()));
        assertFalse(secondIds.contains(insertedAfterSnapshot.toString()));
        assertEquals(
            firstRows.path("totalCount").asLong(),
            second.path("rows").path("totalCount").asLong());
        assertEquals("SNAPSHOT_MEMBERSHIP_VALUES_LIVE_AT_READ",
            second.path("temporalMode").asText());
        JsonNode liveRow = null;
        for (JsonNode item : second.path("rows").path("items")) {
            if (liveValueMonth.toString().equals(
                    item.path("monthId").asText())) {
                liveRow = item;
                break;
            }
        }
        assertTrue(liveRow != null);
        assertEquals("DRAFT", liveRow.path("invoiceState").asText());

        mvc.perform(get("/api/v1/finance/reports")
                .queryParam("cursor", cursor)
                .with(token("user-procurement")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/finance/procurement/control-tower")
                .queryParam("cursor", cursor)
                .with(token("user-finance-ap")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void monthAndInvoiceRoutesUseBoundedSignedSnapshotKeysets()
        throws Exception {
        for (int index = 0; index < 55; index++) {
            UUID monthId = UUID.nameUUIDFromBytes(
                ("f05-route-month-" + index)
                    .getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                INSERT INTO engagement_months(
                    id, engagement_id, month_start_date, state,
                    risk_status, created_at, updated_at
                ) VALUES (?, ?::uuid, ?, 'DRAFT', 'ON_TRACK',
                          '2020-01-01T00:00:00Z',
                          '2020-01-01T00:00:00Z')
                ON CONFLICT DO NOTHING
                """, monthId, ENGAGEMENT,
                LocalDate.of(1990, 1, 1).plusMonths(index));
        }

        JsonNode firstMonths = mapper.readTree(mvc.perform(
                get("/api/v1/finance/months")
                    .with(token("user-procurement")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(50, firstMonths.path("items").size());
        JsonNode firstMonth = firstMonths.path("items").get(0);
        Long persistedMonthVersion = jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months
            WHERE id = ?::uuid
            """, Long.class, firstMonth.path("monthId").asText());
        assertEquals(persistedMonthVersion, firstMonth.path("version").asLong(),
            "Finance month versions must use the migrated F04 concurrency column.");
        String monthCursor = firstMonths.path("nextCursor").asText();
        assertFalse(monthCursor.isBlank());
        long monthTotal = firstMonths.path("totalCount").asLong();
        UUID insertedMonth = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO engagement_months(
                id, engagement_id, month_start_date, state,
                risk_status, created_at, updated_at
            ) VALUES (?, ?::uuid, '2040-01-01', 'DRAFT', 'ON_TRACK',
                      CURRENT_TIMESTAMP + INTERVAL '1 hour',
                      CURRENT_TIMESTAMP + INTERVAL '1 hour')
            """, insertedMonth, ENGAGEMENT);
        JsonNode secondMonths = mapper.readTree(mvc.perform(
                get("/api/v1/finance/months")
                    .queryParam("cursor", monthCursor)
                    .with(token("user-procurement")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(monthTotal, secondMonths.path("totalCount").asLong());
        assertTrue(ids(firstMonths.path("items"), "monthId").stream()
            .noneMatch(ids(secondMonths.path("items"), "monthId")::contains));
        assertFalse(ids(secondMonths.path("items"), "monthId")
            .contains(insertedMonth.toString()));

        jdbc.update("""
            INSERT INTO memberships(
                id, user_profile_id, organization_id, role_code,
                status, valid_from
            ) VALUES (?, '00000000-0000-0000-0000-000000000231',
                      '00000000-0000-0000-0000-000000000101',
                      'PROCUREMENT_REVIEWER', 'ACTIVE', '2020-01-01')
            """, UUID.randomUUID());
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from
            ) VALUES (?, '00000000-0000-0000-0000-000000000231',
                      '00000000-0000-0000-0000-000000000101',
                      '11000000-0000-0000-0000-000000000007',
                      'ENGAGEMENT',
                      '00000000-0000-0000-0000-000000000402',
                      'ACTIVE', '2020-01-01')
            """, UUID.randomUUID());
        mvc.perform(get("/api/v1/finance/months")
                .queryParam("cursor", monthCursor)
                .with(token("user-procurement")))
            .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/finance/months")
                .queryParam("cursor", tamper(monthCursor))
                .with(token("user-procurement")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/finance/invoices")
                .queryParam("cursor", monthCursor)
                .with(token("user-procurement")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/finance/months")
                .queryParam("cursor", monthCursor)
                .with(token("user-finance-ap")))
            .andExpect(status().isBadRequest());

        seedInvoices();
        JsonNode firstInvoices = mapper.readTree(mvc.perform(
                get("/api/v1/finance/invoices")
                    .queryParam("monthId", MONTH)
                    .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(50, firstInvoices.path("items").size());
        String invoiceCursor = firstInvoices.path("nextCursor").asText();
        assertFalse(invoiceCursor.isBlank());
        long invoiceTotal = firstInvoices.path("totalCount").asLong();
        UUID insertedInvoice = insertCreditInvoice(
            UUID.randomUUID(), primaryInvoiceId(),
            "POST SNAPSHOT CREDIT", "post-snapshot-credit",
            "2040-01-01T00:00:00Z");
        JsonNode secondInvoices = mapper.readTree(mvc.perform(
                get("/api/v1/finance/invoices")
                    .queryParam("monthId", MONTH)
                    .queryParam("cursor", invoiceCursor)
                    .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(invoiceTotal, secondInvoices.path("totalCount").asLong());
        assertTrue(ids(firstInvoices.path("items"), "invoiceId").stream()
            .noneMatch(ids(secondInvoices.path("items"), "invoiceId")::contains));
        assertFalse(ids(secondInvoices.path("items"), "invoiceId")
            .contains(insertedInvoice.toString()));
        mvc.perform(get("/api/v1/finance/invoices")
                .queryParam("monthId", MONTH)
                .queryParam("cursor", tamper(invoiceCursor))
                .with(token("user-arrow")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/finance/months")
                .queryParam("cursor", invoiceCursor)
                .with(token("user-arrow")))
            .andExpect(status().isBadRequest());
    }

    private Set<String> ids(JsonNode items) {
        return ids(items, "monthId");
    }

    private Set<String> ids(JsonNode items, String idField) {
        Set<String> values = new HashSet<>();
        items.forEach(item -> values.add(item.path(idField).asText()));
        return values;
    }

    private void seedInvoices() {
        UUID primaryId = primaryInvoiceId();
        jdbc.update("""
            INSERT INTO invoices(
                id, engagement_month_id, vendor_organization_id,
                invoice_type, invoice_number, normalized_invoice_number,
                invoice_date, billing_period_start, billing_period_end,
                currency, taxable_value, tax_value, total_value,
                status, current_version, optimistic_version,
                created_by_subject, created_at, updated_at, correlation_id
            ) VALUES (?, ?::uuid, '00000000-0000-0000-0000-000000000101',
                      'PRIMARY', 'PAGINATION PRIMARY', 'pagination primary',
                      '2026-07-31', '2026-07-01', '2026-07-31',
                      'INR', 100, 18, 118, 'DRAFT', 1, 1,
                      'user-arrow', '2019-01-01T00:00:00Z',
                      '2019-01-01T00:00:00Z', ?)
            """, primaryId, MONTH, UUID.randomUUID());
        for (int index = 0; index < 55; index++) {
            insertCreditInvoice(
                UUID.nameUUIDFromBytes(
                    ("f05-route-invoice-" + index)
                        .getBytes(StandardCharsets.UTF_8)),
                primaryId, "PAGINATION CREDIT " + index,
                "pagination credit " + index,
                "2020-01-01T00:00:%02dZ".formatted(index));
        }
    }

    private UUID insertCreditInvoice(
        UUID invoiceId,
        UUID primaryId,
        String invoiceNumber,
        String normalizedNumber,
        String createdAt
    ) {
        jdbc.update("""
            INSERT INTO invoices(
                id, engagement_month_id, vendor_organization_id,
                invoice_type, invoice_number, normalized_invoice_number,
                invoice_date, billing_period_start, billing_period_end,
                currency, taxable_value, tax_value, total_value,
                status, current_version, optimistic_version,
                note_for_invoice_id, created_by_subject,
                created_at, updated_at, correlation_id
            ) VALUES (?, ?::uuid, '00000000-0000-0000-0000-000000000101',
                      'CREDIT_NOTE', ?, ?, '2026-07-31',
                      '2026-07-01', '2026-07-31',
                      'INR', 1, 0.18, 1.18, 'DRAFT', 1, 1,
                      ?, 'user-arrow', ?::timestamptz,
                      ?::timestamptz, ?)
            """, invoiceId, MONTH, invoiceNumber, normalizedNumber,
            primaryId, createdAt, createdAt, UUID.randomUUID());
        return invoiceId;
    }

    private UUID primaryInvoiceId() {
        return UUID.nameUUIDFromBytes(
            "f05-route-primary".getBytes(StandardCharsets.UTF_8));
    }

    private String tamper(String cursor) {
        char first = cursor.charAt(0);
        return (first == 'A' ? "B" : "A") + cursor.substring(1);
    }
}

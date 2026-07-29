package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GreytHrProviderPayloadValidatorTest {
    private static final LocalDate FROM = LocalDate.parse("2026-07-01");
    private static final LocalDate TO = LocalDate.parse("2026-07-31");
    private static final OffsetDateTime UPDATED =
        OffsetDateTime.parse("2026-07-10T12:00:00Z");

    private final GreytHrProviderPayloadValidator validator =
        new GreytHrProviderPayloadValidator(new ObjectMapper());

    @Test
    void parsesTypedMultiPagePayloadWithGlobalEmployeeMapping() {
        var result = available(
            page(1, UPDATED, Map.of(
                "employees", List.of(employee()),
                "attendance", List.of(),
                "leave", List.of())),
            page(2, UPDATED.plusMinutes(1), Map.of(
                "employees", List.of(Map.of(
                    "providerRecordId", "employee-af-001-v2",
                    "providerEmployeeId", "GHR-AF-001",
                    "employeeNumber", "AF-001",
                    "workEmail", "employee.updated@arrowfoundry.example")),
                "attendance", List.of(attendance()),
                "leave", List.of(leave()))));

        var parsed = validator.validateAndParse("july-sync", FROM, TO, result);

        assertEquals(2, parsed.pages().size());
        assertEquals(1, parsed.employeeMappings().size());
        assertEquals("AF-001", parsed.employeeMappings()
            .get("GHR-AF-001").employeeNumber());
        assertEquals(4, parsed.totalRecords());
        assertEquals(
            "employee-af-001-v2",
            parsed.pages().get(1).employees().getFirst().providerRecordId());
        assertEquals(1, parsed.pages().get(1).attendance().size());
        assertEquals(1, parsed.pages().get(1).leave().size());
    }

    @Test
    void rejectsNoncanonicalIdempotencyAndInvalidOrUnboundedRanges() {
        assertCode("GREYTHR_SYNC_REQUEST_INVALID", () ->
            validator.validateRequest(" ", FROM, TO));
        assertCode("GREYTHR_SYNC_REQUEST_INVALID", () ->
            validator.validateRequest(" leading", FROM, TO));
        assertCode("GREYTHR_SYNC_REQUEST_INVALID", () ->
            validator.validateRequest("line\nbreak", FROM, TO));
        assertCode("GREYTHR_SYNC_REQUEST_INVALID", () ->
            validator.validateRequest("x".repeat(256), FROM, TO));
        assertCode("GREYTHR_SYNC_REQUEST_INVALID", () ->
            validator.validateRequest("valid", TO, FROM));
        assertCode("GREYTHR_SYNC_REQUEST_INVALID", () ->
            validator.validateRequest(
                "valid", FROM, FROM.plusDays(367)));
    }

    @Test
    void rejectsPageGapsDuplicatesTimestampRegressionAndCountLimits() {
        assertCode("GREYTHR_PROVIDER_MALFORMED", () ->
            validate(available(page(2, UPDATED, validPayload()))));
        assertCode("GREYTHR_PROVIDER_MALFORMED", () ->
            validate(available(
                page(1, UPDATED, validPayload()),
                page(1, UPDATED, validPayload()))));
        assertCode("GREYTHR_PROVIDER_MALFORMED", () ->
            validate(available(
                page(1, UPDATED, validPayload()),
                page(2, UPDATED.minusSeconds(1), validPayload()))));

        List<GreytHrProviderAdapter.ProviderPage> pages = new ArrayList<>();
        for (int index = 1; index <= 101; index++) {
            pages.add(page(index, UPDATED.plusMinutes(index), validPayload()));
        }
        assertCode("GREYTHR_PROVIDER_LIMIT_EXCEEDED", () ->
            validate(GreytHrProviderAdapter.FetchResult.success(pages)));

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int index = 0; index <= 10_000; index++) {
            tooMany.add(employee());
        }
        assertCode("GREYTHR_PROVIDER_LIMIT_EXCEEDED", () ->
            validate(available(page(1, UPDATED, Map.of(
                "employees", tooMany,
                "attendance", List.of(),
                "leave", List.of())))));
    }

    @Test
    void rejectsUnknownAndNestedCommercialOrRestrictedFields() {
        assertCode("GREYTHR_PROVIDER_MALFORMED", () ->
            validate(available(page(1, UPDATED, Map.of(
                "employees", List.of(Map.of(
                    "providerRecordId", "employee-1",
                    "providerEmployeeId", "GHR-AF-001",
                    "employeeNumber", "AF-001",
                    "department", "Engineering")),
                "attendance", List.of(),
                "leave", List.of())))));

        for (String prohibited : List.of(
            "salary_amount", "salaryBand", "markup_percent", "commercialRate",
            "billingRate", "hourly_rate", "costToCompanyRate", "payroll",
            "compensation", "bankAccount", "CTC", "aadhaarNumber",
            "employeeRate"
        )) {
            assertCode("GREYTHR_COMMERCIAL_FIELD_PROHIBITED", () ->
                validate(available(page(1, UPDATED, Map.of(
                    "employees", List.of(employee()),
                    "attendance", List.of(),
                    "leave", List.of(),
                    "nested", Map.of(prohibited, "restricted"))))),
                prohibited);
        }
    }

    @Test
    void rejectsOutOfRangeFactsTimestampAbuseAndInvalidLeaveUnits() {
        Map<String, Object> outside = new java.util.LinkedHashMap<>(attendance());
        outside.put("workDate", "2026-08-01");
        outside.put("checkInAt", "2026-08-01T09:00:00Z");
        outside.put("checkOutAt", "2026-08-01T10:00:00Z");
        assertCode("GREYTHR_PROVIDER_DATE_OUT_OF_RANGE", () ->
            validate(payloadWith("attendance", outside)));

        for (Map<String, Object> invalid : List.of(
            attendanceWithTimes(
                "2026-07-08T10:00:00Z", "2026-07-08T10:00:00Z"),
            attendanceWithTimes(
                "2026-07-08T10:00:00Z", "2026-07-09T10:00:01Z"),
            attendanceWithTimes(
                "2026-07-09T00:00:00Z", "2026-07-09T01:00:00Z")
        )) {
            assertCode("GREYTHR_PROVIDER_MALFORMED", () ->
                validate(payloadWith("attendance", invalid)));
        }

        for (Object units : List.of(0, -0.5, 1.0001, "NaN")) {
            Map<String, Object> invalid = new java.util.LinkedHashMap<>(leave());
            invalid.put("units", units);
            assertCode("GREYTHR_PROVIDER_MALFORMED", () ->
                validate(payloadWith("leave", invalid)));
        }
    }

    @Test
    void rejectsOversizedPayloadBeforeAnyRecordCanBePersisted() {
        Map<String, Object> oversized = new java.util.LinkedHashMap<>(employee());
        oversized.put("workEmail", "x".repeat(
            GreytHrProviderPayloadValidator.MAX_PAGE_BYTES + 1));
        assertCode("GREYTHR_PROVIDER_LIMIT_EXCEEDED", () ->
            validate(available(page(1, UPDATED, Map.of(
                "employees", List.of(oversized),
                "attendance", List.of(),
                "leave", List.of())))));
    }

    private GreytHrProviderAdapter.FetchResult payloadWith(
        String kind,
        Map<String, Object> record
    ) {
        return available(page(1, UPDATED, Map.of(
            "employees", List.of(employee()),
            "attendance", "attendance".equals(kind)
                ? List.of(record) : List.of(),
            "leave", "leave".equals(kind) ? List.of(record) : List.of())));
    }

    private void validate(GreytHrProviderAdapter.FetchResult result) {
        validator.validateAndParse("july-sync", FROM, TO, result);
    }

    private static GreytHrProviderAdapter.FetchResult available(
        GreytHrProviderAdapter.ProviderPage... pages
    ) {
        return GreytHrProviderAdapter.FetchResult.success(List.of(pages));
    }

    private static GreytHrProviderAdapter.ProviderPage page(
        int number,
        OffsetDateTime updatedAt,
        Map<String, Object> payload
    ) {
        return new GreytHrProviderAdapter.ProviderPage(
            number, updatedAt, payload);
    }

    private static Map<String, Object> validPayload() {
        return Map.of(
            "employees", List.of(employee()),
            "attendance", List.of(),
            "leave", List.of());
    }

    private static Map<String, Object> employee() {
        return Map.of(
            "providerRecordId", "employee-af-001-v1",
            "providerEmployeeId", "GHR-AF-001",
            "employeeNumber", "AF-001",
            "workEmail", "employee@arrowfoundry.example");
    }

    private static Map<String, Object> attendance() {
        return Map.of(
            "providerRecordId", "attendance-af-001-2026-07-08",
            "providerEmployeeId", "GHR-AF-001",
            "workDate", "2026-07-08",
            "checkInAt", "2026-07-08T09:00:00Z",
            "checkOutAt", "2026-07-08T17:00:00Z");
    }

    private static Map<String, Object> attendanceWithTimes(
        String checkIn,
        String checkOut
    ) {
        Map<String, Object> result =
            new java.util.LinkedHashMap<>(attendance());
        result.put("checkInAt", checkIn);
        result.put("checkOutAt", checkOut);
        return result;
    }

    private static Map<String, Object> leave() {
        return Map.of(
            "providerRecordId", "leave-af-001-2026-07-09",
            "providerEmployeeId", "GHR-AF-001",
            "workDate", "2026-07-09",
            "leaveTypeCode", "CL",
            "units", 0.5);
    }

    private void assertCode(String expected, Runnable action) {
        assertCode(expected, action, expected);
    }

    private void assertCode(
        String expected,
        Runnable action,
        String message
    ) {
        DomainConflictException error =
            assertThrows(DomainConflictException.class, action::run, message);
        assertEquals(expected, error.getCode(), message);
    }
}

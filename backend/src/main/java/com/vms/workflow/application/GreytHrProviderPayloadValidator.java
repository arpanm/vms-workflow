package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class GreytHrProviderPayloadValidator {
    static final int MAX_PAGES = 100;
    static final int MAX_RECORDS_PER_PAGE = 10_000;
    static final int MAX_TOTAL_RECORDS = 100_000;
    static final int MAX_PAGE_BYTES = 1_048_576;
    static final int MAX_TOTAL_BYTES = 10_485_760;
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
    static final long MAX_RANGE_DAYS = 366;
    static final Duration MAX_ATTENDANCE_DURATION = Duration.ofHours(24);

    private static final Set<String> PAGE_FIELDS =
        Set.of("employees", "attendance", "leave");
    private static final Set<String> EMPLOYEE_FIELDS =
        Set.of(
            "providerRecordId", "providerEmployeeId", "employeeNumber",
            "workEmail");
    private static final Set<String> ATTENDANCE_FIELDS =
        Set.of(
            "providerRecordId", "providerEmployeeId", "workDate",
            "checkInAt", "checkOutAt");
    private static final Set<String> LEAVE_FIELDS =
        Set.of(
            "providerRecordId", "providerEmployeeId", "workDate",
            "leaveTypeCode", "units");
    private static final Set<String> RESTRICTED_EXACT_FIELDS =
        Set.of(
            "accountnumber", "amount", "bankaccount", "bankrouting",
            "commercialterms", "cost", "ctc", "currency", "iban", "invoice",
            "invoicenumber", "margin", "nationalid", "pannumber", "payroll",
            "ponumber", "price", "purchaseorder", "rate", "rateamount",
            "rateband", "ratecard", "ratepercent", "rates", "ratevalue",
            "routingnumber", "ssn", "swift", "taxid");
    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}");

    private final ObjectMapper mapper;

    public GreytHrProviderPayloadValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void validateRequest(
        String idempotencyKey,
        LocalDate dateFrom,
        LocalDate dateTo
    ) {
        if (idempotencyKey == null
            || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            reject(
                "GREYTHR_SYNC_REQUEST_INVALID",
                "Idempotency-Key must be nonblank, canonical, and at most 255 characters.");
        }
        if (dateFrom == null
            || dateTo == null
            || dateTo.isBefore(dateFrom)
            || ChronoUnit.DAYS.between(dateFrom, dateTo) > MAX_RANGE_DAYS) {
            reject(
                "GREYTHR_SYNC_REQUEST_INVALID",
                "The requested date range must be ordered and at most 366 days.");
        }
    }

    public ValidatedPayload validateAndParse(
        String idempotencyKey,
        LocalDate dateFrom,
        LocalDate dateTo,
        GreytHrProviderAdapter.FetchResult result
    ) {
        validateRequest(idempotencyKey, dateFrom, dateTo);
        if (result == null || !"AVAILABLE".equals(result.status())) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "Only an available provider result can be parsed.");
        }
        List<GreytHrProviderAdapter.ProviderPage> sourcePages = result.pages();
        if (sourcePages == null
            || sourcePages.isEmpty()
            || sourcePages.size() > MAX_PAGES) {
            limit("Provider response page count is outside the certified limit.");
        }

        List<GreytHrProviderAdapter.ProviderPage> ordered =
            sourcePages.stream()
                .sorted(java.util.Comparator.comparingInt(
                    GreytHrProviderAdapter.ProviderPage::pageNumber))
                .toList();
        Map<String, EmployeeRecord> mappings = new LinkedHashMap<>();
        Map<String, EmployeeRecord> employeesByRecordId =
            new HashMap<>();
        Map<Integer, PageLists> rawPages = new LinkedHashMap<>();
        Set<String> recordIdentities = new HashSet<>();
        OffsetDateTime priorSourceTimestamp = null;
        int totalBytes = 0;
        int totalRecords = 0;

        for (int index = 0; index < ordered.size(); index++) {
            GreytHrProviderAdapter.ProviderPage page = ordered.get(index);
            if (page == null
                || page.pageNumber() != index + 1
                || page.sourceUpdatedAt() == null
                || page.payload() == null) {
                reject(
                    "GREYTHR_PROVIDER_MALFORMED",
                    "Provider pages must be contiguous from page one with timestamps.");
            }
            if (priorSourceTimestamp != null
                && page.sourceUpdatedAt().isBefore(priorSourceTimestamp)) {
                reject(
                    "GREYTHR_PROVIDER_MALFORMED",
                    "Provider page timestamps must be nondecreasing.");
            }
            priorSourceTimestamp = page.sourceUpdatedAt();
            prohibitCommercialFields(page.payload());
            exactFields(page.payload(), PAGE_FIELDS, "provider page");

            int pageBytes = mapper.writeValueAsBytes(page.payload()).length;
            if (pageBytes > MAX_PAGE_BYTES) {
                limit("A provider page exceeds the one MiB payload limit.");
            }
            totalBytes = Math.addExact(totalBytes, pageBytes);
            if (totalBytes > MAX_TOTAL_BYTES) {
                limit("Provider response exceeds the ten MiB payload limit.");
            }

            List<Map<String, Object>> employees =
                records(page.payload(), "employees");
            List<Map<String, Object>> attendance =
                records(page.payload(), "attendance");
            List<Map<String, Object>> leave = records(page.payload(), "leave");
            int pageRecords =
                employees.size() + attendance.size() + leave.size();
            if (pageRecords > MAX_RECORDS_PER_PAGE) {
                limit("A provider page exceeds the record-count limit.");
            }
            totalRecords = Math.addExact(totalRecords, pageRecords);
            if (totalRecords > MAX_TOTAL_RECORDS) {
                limit("Provider response exceeds the total record-count limit.");
            }
            if (pageRecords == 0) {
                reject(
                    "GREYTHR_PROVIDER_MALFORMED",
                    "Provider pages cannot be empty.");
            }
            rawPages.put(
                page.pageNumber(),
                new PageLists(page, employees, attendance, leave));

            for (Map<String, Object> employee : employees) {
                prohibitCommercialFields(employee);
                exactFields(employee, EMPLOYEE_FIELDS, "employee record");
                EmployeeRecord typed = new EmployeeRecord(
                    boundedText(employee, "providerRecordId", 255),
                    boundedText(employee, "providerEmployeeId", 128),
                    boundedText(employee, "employeeNumber", 64),
                    optionalBoundedText(employee, "workEmail", 320),
                    immutableCopy(employee));
                uniqueRecord(
                    recordIdentities, "EMPLOYEE", typed.providerRecordId());
                employeesByRecordId.put(typed.providerRecordId(), typed);
                EmployeeRecord prior =
                    mappings.putIfAbsent(typed.providerEmployeeId(), typed);
                if (prior != null
                    && !prior.employeeNumber().equals(typed.employeeNumber())) {
                    reject(
                        "GREYTHR_EMPLOYEE_MAPPING_CONFLICT",
                        "A provider employee maps to multiple employee numbers.");
                }
            }
        }

        List<ValidatedPage> parsedPages = new ArrayList<>();
        for (PageLists page : rawPages.values()) {
            List<EmployeeRecord> employees = page.employees().stream()
                .map(record -> employeesByRecordId.get(
                    boundedText(record, "providerRecordId", 255)))
                .toList();
            List<AttendanceRecord> attendance = new ArrayList<>();
            for (Map<String, Object> record : page.attendance()) {
                prohibitCommercialFields(record);
                exactFields(record, ATTENDANCE_FIELDS, "attendance record");
                String recordId =
                    boundedText(record, "providerRecordId", 255);
                String providerEmployeeId =
                    boundedText(record, "providerEmployeeId", 128);
                requireMapping(mappings, providerEmployeeId);
                LocalDate workDate = date(
                    record, "workDate", dateFrom, dateTo);
                OffsetDateTime checkIn = timestamp(record, "checkInAt");
                OffsetDateTime checkOut = timestamp(record, "checkOutAt");
                Duration duration = Duration.between(checkIn, checkOut);
                if (duration.isNegative()
                    || duration.isZero()
                    || duration.compareTo(MAX_ATTENDANCE_DURATION) > 0
                    || !checkIn.toLocalDate().equals(workDate)
                    || checkOut.toLocalDate().isBefore(workDate)
                    || checkOut.toLocalDate().isAfter(workDate.plusDays(1))) {
                    reject(
                        "GREYTHR_PROVIDER_MALFORMED",
                        "Attendance timestamps are unordered or exceed 24 hours.");
                }
                uniqueRecord(recordIdentities, "ATTENDANCE", recordId);
                attendance.add(new AttendanceRecord(
                    recordId, providerEmployeeId, workDate, checkIn, checkOut,
                    immutableCopy(record)));
            }
            List<LeaveRecord> leave = new ArrayList<>();
            for (Map<String, Object> record : page.leave()) {
                prohibitCommercialFields(record);
                exactFields(record, LEAVE_FIELDS, "leave record");
                String recordId =
                    boundedText(record, "providerRecordId", 255);
                String providerEmployeeId =
                    boundedText(record, "providerEmployeeId", 128);
                requireMapping(mappings, providerEmployeeId);
                LocalDate workDate =
                    date(record, "workDate", dateFrom, dateTo);
                String leaveType =
                    boundedText(record, "leaveTypeCode", 32);
                BigDecimal units = decimal(record, "units");
                if (units.signum() <= 0
                    || units.compareTo(BigDecimal.ONE) > 0
                    || units.scale() > 4) {
                    reject(
                        "GREYTHR_PROVIDER_MALFORMED",
                        "Leave units must be finite, positive, and at most one day.");
                }
                uniqueRecord(recordIdentities, "LEAVE", recordId);
                leave.add(new LeaveRecord(
                    recordId, providerEmployeeId, workDate, leaveType, units,
                    immutableCopy(record)));
            }
            parsedPages.add(new ValidatedPage(
                page.source().pageNumber(), page.source().sourceUpdatedAt(),
                employees, List.copyOf(attendance), List.copyOf(leave)));
        }
        return new ValidatedPayload(
            List.copyOf(parsedPages), Map.copyOf(mappings), totalRecords,
            totalBytes);
    }

    private void exactFields(
        Map<String, Object> value,
        Set<String> allowlist,
        String kind
    ) {
        Set<String> unexpected = new HashSet<>(value.keySet());
        unexpected.removeAll(allowlist);
        if (!unexpected.isEmpty()) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "The " + kind + " contains fields outside the certified schema.");
        }
    }

    private void prohibitCommercialFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey())
                    .replaceAll("[^A-Za-z0-9]", "")
                    .toLowerCase(java.util.Locale.ROOT);
                if (isRestrictedKey(key)) {
                    reject(
                        "GREYTHR_COMMERCIAL_FIELD_PROHIBITED",
                        "Commercial and restricted fields are prohibited in workforce payloads.");
                }
                prohibitCommercialFields(entry.getValue());
            }
        } else if (value instanceof Iterable<?> values) {
            values.forEach(this::prohibitCommercialFields);
        }
    }

    private boolean isRestrictedKey(String normalizedKey) {
        return RESTRICTED_EXACT_FIELDS.contains(normalizedKey)
            || normalizedKey.contains("salary")
            || normalizedKey.contains("markup")
            || normalizedKey.contains("payroll")
            || normalizedKey.contains("compensation")
            || normalizedKey.contains("bankaccount")
            || normalizedKey.contains("aadhaar")
            || normalizedKey.matches(
                "^(billing|cost|hourly|commercial|vendor|client|employee).*rate.*$")
            || normalizedKey.matches(
                "^(base|gross|net|annual|monthly|daily|pay).*salary.*$");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(
        Map<String, Object> payload,
        String key
    ) {
        Object value = payload.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "Provider record collections must be arrays.");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) {
                reject(
                    "GREYTHR_PROVIDER_MALFORMED",
                    "Provider array entries must be objects.");
            }
            Map<?, ?> map = (Map<?, ?>) item;
            for (Object rawKey : ((Map<?, ?>) item).keySet()) {
                if (!(rawKey instanceof String)) {
                    reject(
                        "GREYTHR_PROVIDER_MALFORMED",
                        "Provider object keys must be strings.");
                }
            }
            records.add((Map<String, Object>) map);
        }
        return List.copyOf(records);
    }

    private String boundedText(
        Map<String, Object> record,
        String key,
        int maxLength
    ) {
        Object value = record.get(key);
        if (!(value instanceof String text)
            || text.isBlank()
            || !text.equals(text.trim())
            || text.codePoints().anyMatch(Character::isISOControl)
            || text.length() > maxLength) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "A provider identifier is missing, noncanonical, or too long.");
        }
        return (String) value;
    }

    private String optionalBoundedText(
        Map<String, Object> record,
        String key,
        int maxLength
    ) {
        if (!record.containsKey(key)) {
            return null;
        }
        return boundedText(record, key, maxLength);
    }

    private LocalDate date(
        Map<String, Object> record,
        String key,
        LocalDate dateFrom,
        LocalDate dateTo
    ) {
        try {
            LocalDate parsed = LocalDate.parse(boundedText(record, key, 10));
            if (parsed.isBefore(dateFrom) || parsed.isAfter(dateTo)) {
                reject(
                    "GREYTHR_PROVIDER_DATE_OUT_OF_RANGE",
                    "A provider fact is outside the requested date range.");
            }
            return parsed;
        } catch (java.time.DateTimeException exception) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "A provider work date is invalid.");
            throw new IllegalStateException("unreachable", exception);
        }
    }

    private OffsetDateTime timestamp(
        Map<String, Object> record,
        String key
    ) {
        try {
            return OffsetDateTime.parse(boundedText(record, key, 64));
        } catch (java.time.DateTimeException exception) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "A provider timestamp is invalid.");
            throw new IllegalStateException("unreachable", exception);
        }
    }

    private BigDecimal decimal(Map<String, Object> record, String key) {
        Object raw = record.get(key);
        if (!(raw instanceof Number)) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "Leave units must be numeric.");
        }
        try {
            BigDecimal value = new BigDecimal(String.valueOf(raw));
            if (value.toString().equals("NaN")
                || value.toString().contains("Infinity")) {
                reject(
                    "GREYTHR_PROVIDER_MALFORMED",
                    "Leave units must be finite.");
            }
            return value.stripTrailingZeros();
        } catch (NumberFormatException exception) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "Leave units must be finite.");
            throw new IllegalStateException("unreachable", exception);
        }
    }

    private void requireMapping(
        Map<String, EmployeeRecord> mappings,
        String providerEmployeeId
    ) {
        if (!mappings.containsKey(providerEmployeeId)) {
            reject(
                "GREYTHR_EMPLOYEE_UNMAPPED",
                "A provider fact references an employee absent from all pages.");
        }
    }

    private void uniqueRecord(
        Set<String> identities,
        String kind,
        String providerRecordId
    ) {
        if (!identities.add(kind + ":" + providerRecordId)) {
            reject(
                "GREYTHR_PROVIDER_MALFORMED",
                "Provider record identifiers must be unique per fact kind.");
        }
    }

    private Map<String, Object> immutableCopy(Map<String, Object> value) {
        return java.util.Collections.unmodifiableMap(
            new LinkedHashMap<>(value));
    }

    private void limit(String message) {
        reject("GREYTHR_PROVIDER_LIMIT_EXCEEDED", message);
    }

    private void reject(String code, String message) {
        throw new DomainConflictException(code, message);
    }

    private record PageLists(
        GreytHrProviderAdapter.ProviderPage source,
        List<Map<String, Object>> employees,
        List<Map<String, Object>> attendance,
        List<Map<String, Object>> leave
    ) {
    }

    public record EmployeeRecord(
        String providerRecordId,
        String providerEmployeeId,
        String employeeNumber,
        String workEmail,
        Map<String, Object> raw
    ) {
    }

    public record AttendanceRecord(
        String providerRecordId,
        String providerEmployeeId,
        LocalDate workDate,
        OffsetDateTime checkInAt,
        OffsetDateTime checkOutAt,
        Map<String, Object> raw
    ) {
    }

    public record LeaveRecord(
        String providerRecordId,
        String providerEmployeeId,
        LocalDate workDate,
        String leaveTypeCode,
        BigDecimal units,
        Map<String, Object> raw
    ) {
    }

    public record ValidatedPage(
        int pageNumber,
        OffsetDateTime sourceUpdatedAt,
        List<EmployeeRecord> employees,
        List<AttendanceRecord> attendance,
        List<LeaveRecord> leave
    ) {
    }

    public record ValidatedPayload(
        List<ValidatedPage> pages,
        Map<String, EmployeeRecord> employeeMappings,
        int totalRecords,
        int totalBytes
    ) {
    }
}

package com.vms.workflow.integration;

import com.vms.workflow.api.AttendanceDtos.PunchRequest;
import com.vms.workflow.api.AttendanceDtos.PunchView;
import com.vms.workflow.api.WorkforceDtos.EmployeeView;
import com.vms.workflow.application.AttendanceService;
import com.vms.workflow.application.WorkforceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.hikari.maximum-pool-size=16",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.certification.worker-initial-delay=PT1H"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class F07CapacityPerformanceIT {
    private static final UUID ORGANIZATION =
        UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final int EMPLOYEE_COUNT = 10_000;
    private static final int ATTENDANCE_USER_COUNT = 26;
    private static final int HISTORY_DAYS = 30;

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(
            DockerImageName.parse(
                "cgr.dev/chainguard/postgres@sha256:"
                    + "dc2f04037c1044a22af76cee4de70b9111885b17c561b93"
                    + "9d7ed70103d100759")
                .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("vms_workflow_f07_capacity")
            .withUsername("test")
            .withPassword("test")
            .withCommand("-c", "fsync=off")
            .waitingFor(Wait.forLogMessage(
                ".*database system is ready to accept connections.*\\s", 2)
                .withStartupTimeout(Duration.ofMinutes(3)))
            .withStartupTimeout(Duration.ofMinutes(3));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AttendanceService attendance;

    @Autowired
    private WorkforceService workforce;

    @BeforeAll
    void seedTargetProfile() {
        jdbc.update("""
            INSERT INTO user_profiles
                (id, identity_subject, email, display_name, status)
            SELECT md5('f07-capacity-user-' || value)::uuid,
                   'f07-capacity-user-' || value,
                   'f07-capacity-user-' || value || '@example.test',
                   'F07 Capacity User ' || value,
                   'ACTIVE'
            FROM generate_series(2, 26) AS value
            """);
        jdbc.update("""
            INSERT INTO memberships
                (id, user_profile_id, organization_id, role_code, status,
                 valid_from, valid_to)
            SELECT md5('f07-capacity-membership-' || value)::uuid,
                   md5('f07-capacity-user-' || value)::uuid,
                   ?, 'EMPLOYEE', 'ACTIVE', DATE '2020-01-01', NULL
            FROM generate_series(2, 26) AS value
            """, ORGANIZATION);
        jdbc.update("""
            INSERT INTO role_assignments
                (id, user_profile_id, organization_id, role_id, scope_type,
                 scope_id, status, valid_from, valid_to)
            SELECT md5('f07-capacity-role-' || value)::uuid,
                   md5('f07-capacity-user-' || value)::uuid,
                   ?, '11000000-0000-0000-0000-000000000011'::uuid,
                   'ORGANIZATION', ?, 'ACTIVE', DATE '2020-01-01', NULL
            FROM generate_series(2, 26) AS value
            """, ORGANIZATION, ORGANIZATION);
        jdbc.update("""
            INSERT INTO employees
                (id, organization_id, employee_number, work_email,
                 user_profile_id, join_date, created_by_subject)
            SELECT md5('f07-capacity-employee-' || value)::uuid,
                   ?, 'F07-' || lpad(value::text, 5, '0'),
                   'f07-capacity-employee-' || value || '@example.test',
                   CASE WHEN value <= 26
                        THEN md5('f07-capacity-user-' || value)::uuid
                        ELSE NULL END,
                   DATE '2020-01-01', 'f07-capacity-fixture'
            FROM generate_series(2, 10000) AS value
            """, ORGANIZATION);
        jdbc.update("""
            INSERT INTO employee_versions
                (id, employee_id, version, valid_from, first_name, last_name,
                 display_name, designation, employment_status,
                 activation_status, recorded_by_subject)
            SELECT md5('f07-capacity-employee-version-' || value)::uuid,
                   md5('f07-capacity-employee-' || value)::uuid,
                   1, DATE '2020-01-01', 'Capacity',
                   'Employee ' || lpad(value::text, 5, '0'),
                   'Capacity Employee ' || lpad(value::text, 5, '0'),
                   'Synthetic capacity fixture', 'ACTIVE', 'ENABLED',
                   'f07-capacity-fixture'
            FROM generate_series(2, 10000) AS value
            """);
        jdbc.update("""
            INSERT INTO attendance_source_mode_assignments
                (id, employee_id, mode, authoritative_source, valid_from,
                 created_by_subject)
            SELECT md5('f07-capacity-source-' || value)::uuid,
                   md5('f07-capacity-employee-' || value)::uuid,
                   'INTERNAL_AUTHORITATIVE', 'INTERNAL',
                   DATE '2020-01-01', 'f07-capacity-fixture'
            FROM generate_series(2, 10000) AS value
            """);
        jdbc.update("""
            INSERT INTO engagements
                (id, engagement_code, name, client_organization_id,
                 vendor_organization_id, procurement_organization_id,
                 engagement_model, start_date, status)
            SELECT md5('f07-capacity-engagement-' || value)::uuid,
                   'F07-CAP-' || lpad(value::text, 3, '0'),
                   'F07 capacity engagement ' || value,
                   '00000000-0000-0000-0000-000000000102'::uuid,
                   ?, NULL, 'DEDICATED_RESOURCE_MONTHLY',
                   DATE '2020-01-01', 'ACTIVE'
            FROM generate_series(1, 500) AS value
            """, ORGANIZATION);
        jdbc.update("""
            INSERT INTO attendance_days
                (id, employee_id, work_date, calculation_version, is_current,
                 expected_classification, expected_minutes, source_mode,
                 net_minutes, leave_units, final_status)
            SELECT gen_random_uuid(),
                   employee.id, CURRENT_DATE - age.day, 1, TRUE,
                   'WORKING', 540, 'INTERNAL_AUTHORITATIVE',
                   540, 0, 'PRESENT_FULL_DAY'
            FROM employees employee
            CROSS JOIN generate_series(31, 60) AS age(day)
            WHERE employee.organization_id = ?
            """, ORGANIZATION);
        jdbc.execute("""
            ANALYZE employees;
            ANALYZE employee_versions;
            ANALYZE attendance_source_mode_assignments;
            ANALYZE attendance_days;
            ANALYZE f05_report_exports
            """);

        assertEquals(EMPLOYEE_COUNT, jdbc.queryForObject("""
            SELECT COUNT(*) FROM employees WHERE organization_id = ?
            """, Integer.class, ORGANIZATION));
        assertEquals(500, jdbc.queryForObject("""
            SELECT COUNT(*) FROM engagements WHERE engagement_code LIKE 'F07-CAP-%'
            """, Integer.class));
        assertEquals(EMPLOYEE_COUNT * HISTORY_DAYS, jdbc.queryForObject("""
            SELECT COUNT(*) FROM attendance_days day
            JOIN employees employee ON employee.id = day.employee_id
            WHERE employee.organization_id = ?
              AND day.work_date BETWEEN CURRENT_DATE - 60 AND CURRENT_DATE - 31
            """, Integer.class, ORGANIZATION));
        assertEquals(5, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM pg_class index_relation
            JOIN pg_index index_catalog
              ON index_catalog.indexrelid = index_relation.oid
            JOIN pg_class table_relation
              ON table_relation.oid = index_catalog.indrelid
            JOIN pg_roles owner_role
              ON owner_role.oid = index_relation.relowner
            WHERE index_relation.relname IN (
                'idx_employee_versions_current_name_search',
                'idx_employee_versions_effective_employee',
                'idx_attendance_source_effective_employee',
                'idx_attendance_days_current_date_employee',
                'idx_f05_report_exports_subject_scope_page'
            )
              AND index_relation.relowner = table_relation.relowner
              AND owner_role.rolname = 'vms_migration_owner'
            """, Integer.class));
    }

    @Test
    @Order(1)
    void f07Perf001_twentySixUsersCheckInWithBoundedConcurrencyAndIdempotentReplay() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            List<EmployeeView> warmEmployees = workforce.employees("user-arrow", ORGANIZATION);
            assertEquals(EMPLOYEE_COUNT, warmEmployees.size());

            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(13)) {
                List<Callable<TimedPunch>> firstWave = new ArrayList<>();
                firstWave.add(() -> timedPunch(
                    "user-employee",
                    UUID.fromString("00000000-0000-0000-0000-000000000801"),
                    "f07-capacity-check-in-1",
                    start));
                for (int value = 2; value <= ATTENDANCE_USER_COUNT; value++) {
                    int employeeNumber = value;
                    firstWave.add(() -> timedPunch(
                        "f07-capacity-user-" + employeeNumber,
                        capacityEmployee(employeeNumber),
                        "f07-capacity-check-in-" + employeeNumber,
                        start));
                }
                Future<TimedRead> concurrentRead = executor.submit(() -> {
                    start.await();
                    long started = System.nanoTime();
                    EmployeeView employee = workforce.employee(
                        "user-arrow",
                        UUID.fromString("00000000-0000-0000-0000-000000000801"));
                    return new TimedRead(elapsedMillis(started), employee);
                });
                List<Future<TimedPunch>> firstFutures = firstWave.stream()
                    .map(executor::submit)
                    .toList();
                start.countDown();

                List<TimedPunch> first = resolve(firstFutures);
                TimedRead read = concurrentRead.get(10, TimeUnit.SECONDS);
                assertEquals(
                    UUID.fromString("00000000-0000-0000-0000-000000000801"),
                    read.employee().id());
                assertTrue(read.durationMs() <= 2_500,
                    () -> "concurrent employee dashboard read exceeded 2500ms: "
                        + read.durationMs() + "ms");
                long checkInP95 =
                    p95(first.stream().map(TimedPunch::durationMs).toList());
                assertTrue(checkInP95 <= 1_500,
                    () -> "26-person durable check-in p95 exceeded 1500ms: "
                        + checkInP95 + "ms");
                assertTrue(first.stream().allMatch(
                    value -> "OPEN".equals(value.punch().sessionStatus())));

                CountDownLatch replayStart = new CountDownLatch(1);
                List<Callable<TimedPunch>> replayWave = new ArrayList<>();
                replayWave.add(() -> timedPunch(
                    "user-employee",
                    UUID.fromString("00000000-0000-0000-0000-000000000801"),
                    "f07-capacity-check-in-1",
                    replayStart));
                for (int value = 2; value <= ATTENDANCE_USER_COUNT; value++) {
                    int employeeNumber = value;
                    replayWave.add(() -> timedPunch(
                        "f07-capacity-user-" + employeeNumber,
                        capacityEmployee(employeeNumber),
                        "f07-capacity-check-in-" + employeeNumber,
                        replayStart));
                }
                List<Future<TimedPunch>> replayFutures = replayWave.stream()
                    .map(executor::submit)
                    .toList();
                replayStart.countDown();
                List<TimedPunch> replay = resolve(replayFutures);
                long replayP95 =
                    p95(replay.stream().map(TimedPunch::durationMs).toList());
                assertTrue(replayP95 <= 1_500);
                System.out.printf(
                    "F07-PERF-001 checkInP95Ms=%d replayP95Ms=%d "
                        + "concurrentDashboardReadMs=%d%n",
                    checkInP95, replayP95, read.durationMs());
            }

            assertEquals(ATTENDANCE_USER_COUNT, jdbc.queryForObject("""
                SELECT COUNT(*) FROM attendance_events
                WHERE idempotency_key LIKE 'f07-capacity-check-in-%'
                """, Integer.class));
            assertEquals(ATTENDANCE_USER_COUNT, jdbc.queryForObject("""
                SELECT COUNT(*) FROM attendance_sessions session
                JOIN attendance_events event
                  ON event.id = session.check_in_event_id
                WHERE event.idempotency_key LIKE 'f07-capacity-check-in-%'
                  AND session.status = 'OPEN'
                """, Integer.class));
        });
    }

    @Test
    @Order(2)
    void f07Perf002_scopedSearchAndReportingUseBoundedIndexedPlans() {
        String searchPlan = explain("""
            SELECT employee.id, version.display_name
            FROM employee_versions version
            JOIN employees employee ON employee.id = version.employee_id
            WHERE employee.organization_id = ?
              AND version.valid_to IS NULL
              AND lower(version.display_name) LIKE 'capacity employee 099%'
            ORDER BY version.display_name
            """, ORGANIZATION);
        assertUsesIndex(searchPlan, "idx_employee_versions_current_name_search");
        assertNoSequentialScan(searchPlan, "employee_versions");

        String reportPlan = explain("""
            SELECT day.work_date, COUNT(*), SUM(day.net_minutes)
            FROM attendance_days day
            JOIN employees employee ON employee.id = day.employee_id
            WHERE employee.organization_id = ?
              AND day.is_current
              AND day.work_date = CURRENT_DATE - 31
            GROUP BY day.work_date
            """, ORGANIZATION);
        assertUsesIndex(reportPlan, "idx_attendance_days_current_date_employee");
        assertNoSequentialScan(reportPlan, "attendance_days");

        jdbc.queryForList("""
            SELECT employee.id, version.display_name
            FROM employee_versions version
            JOIN employees employee ON employee.id = version.employee_id
            WHERE employee.organization_id = ?
              AND version.valid_to IS NULL
              AND lower(version.display_name) LIKE 'capacity employee 099%'
            ORDER BY version.display_name
            """, ORGANIZATION);
        jdbc.queryForList("""
            SELECT day.work_date, COUNT(*), SUM(day.net_minutes)
            FROM attendance_days day
            JOIN employees employee ON employee.id = day.employee_id
            WHERE employee.organization_id = ?
              AND day.is_current
              AND day.work_date = CURRENT_DATE - 31
            GROUP BY day.work_date
            """, ORGANIZATION);

        List<Long> searchDurations = measure(12, () -> jdbc.queryForList("""
            SELECT employee.id, version.display_name
            FROM employee_versions version
            JOIN employees employee ON employee.id = version.employee_id
            WHERE employee.organization_id = ?
              AND version.valid_to IS NULL
              AND lower(version.display_name) LIKE 'capacity employee 099%'
            ORDER BY version.display_name
            """, ORGANIZATION));
        List<Long> reportDurations = measure(12, () -> jdbc.queryForList("""
            SELECT day.work_date, COUNT(*), SUM(day.net_minutes)
            FROM attendance_days day
            JOIN employees employee ON employee.id = day.employee_id
            WHERE employee.organization_id = ?
              AND day.is_current
              AND day.work_date = CURRENT_DATE - 31
            GROUP BY day.work_date
            """, ORGANIZATION));
        assertTrue(p95(searchDurations) <= 2_000,
            () -> "10k employee scoped search exceeded 2000ms p95: "
                + p95(searchDurations) + "ms");
        assertTrue(p95(reportDurations) <= 2_500,
            () -> (EMPLOYEE_COUNT * HISTORY_DAYS)
                + " attendance-row report exceeded 2500ms p95: "
                + p95(reportDurations) + "ms");
        System.out.printf(
            "F07-PERF-002 employeeCount=%d attendanceRows=%d "
                + "searchP95Ms=%d reportP95Ms=%d%n",
            EMPLOYEE_COUNT, EMPLOYEE_COUNT * HISTORY_DAYS,
            p95(searchDurations), p95(reportDurations));

        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM employees employee
            JOIN employee_versions version ON version.employee_id = employee.id
            WHERE employee.organization_id <> ?
              AND version.display_name LIKE 'Capacity Employee %'
            """, Integer.class, ORGANIZATION));
    }

    private TimedPunch timedPunch(String subject, UUID employeeId,
                                  String idempotencyKey, CountDownLatch start)
        throws Exception {
        start.await();
        long started = System.nanoTime();
        PunchView result = attendance.punch(
            subject, new PunchRequest(employeeId, "CHECK_IN", idempotencyKey));
        return new TimedPunch(elapsedMillis(started), result);
    }

    private List<TimedPunch> resolve(List<Future<TimedPunch>> futures) throws Exception {
        List<TimedPunch> results = new ArrayList<>();
        for (Future<TimedPunch> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        return results;
    }

    private List<Long> measure(int repetitions, Runnable action) {
        List<Long> durations = new ArrayList<>(repetitions);
        for (int index = 0; index < repetitions; index++) {
            long started = System.nanoTime();
            action.run();
            durations.add(elapsedMillis(started));
        }
        return durations;
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private long p95(List<Long> durations) {
        List<Long> ordered = durations.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(ordered.size() * 0.95) - 1);
        return ordered.get(index);
    }

    private UUID capacityEmployee(int value) {
        return jdbc.queryForObject(
            "SELECT md5('f07-capacity-employee-' || ?::text)::uuid",
            UUID.class, value);
    }

    private String explain(String sql, Object... parameters) {
        return jdbc.queryForObject(
            "EXPLAIN (FORMAT JSON) " + sql, String.class, parameters);
    }

    private void assertUsesIndex(String rawPlan, String indexName) {
        JsonNode plan = mapper.readTree(rawPlan);
        assertTrue(containsFieldValue(plan, "Index Name", indexName),
            () -> "expected plan to use " + indexName + ": " + rawPlan);
    }

    private void assertNoSequentialScan(String rawPlan, String relationName) {
        JsonNode plan = mapper.readTree(rawPlan);
        assertFalse(containsSequentialScan(plan, relationName),
            () -> "unbounded sequential scan on " + relationName + ": " + rawPlan);
    }

    private boolean containsFieldValue(JsonNode node, String field, String expected) {
        if (node.isObject() && expected.equals(node.path(field).asText())) {
            return true;
        }
        for (JsonNode child : node) {
            if (containsFieldValue(child, field, expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSequentialScan(JsonNode node, String relationName) {
        if (node.isObject()
            && "Seq Scan".equals(node.path("Node Type").asText())
            && relationName.equals(node.path("Relation Name").asText())) {
            return true;
        }
        for (JsonNode child : node) {
            if (containsSequentialScan(child, relationName)) {
                return true;
            }
        }
        return false;
    }

    private record TimedPunch(long durationMs, PunchView punch) {
    }

    private record TimedRead(long durationMs, EmployeeView employee) {
    }
}

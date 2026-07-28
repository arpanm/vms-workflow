package com.vms.workflow.application;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationCsvParserTest {
    private final MigrationCsvParser parser = new MigrationCsvParser();

    @Test
    void parsesBomQuotedCommaNewlineAndEscapedQuoteDeterministically()
        throws Exception {
        var rows = parser.parse(new StringReader(
            "\ufeffa,b\r\n1,\"comma, newline\nand \"\"quote\"\"\"\r\n"), 10);

        assertEquals(2, rows.size());
        assertEquals(1, rows.getFirst().physicalLine());
        assertEquals(2, rows.get(1).physicalLine());
        assertEquals("comma, newline\nand \"quote\"",
            rows.get(1).fields().get(1));
    }

    @Test
    void rejectsUnterminatedQuotesAndBoundedRows() {
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse(new StringReader("a\n\"broken"), 10));
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse(new StringReader("a\n1\n2\n"), 1));
    }

    @Test
    void registryContainsEveryPhysicalTemplateInStableDependencyOrder()
        throws Exception {
        MigrationTemplateRegistry registry = new MigrationTemplateRegistry();

        assertEquals(14, registry.all().size());
        assertEquals("01_employees", registry.all().getFirst().code());
        assertEquals("13_approval_history", registry.all().getLast().code());
        assertTrue(registry.require("07b_attendance_daily")
            .dependencies().contains("06_leave_requests"));
        byte[] sample = registry.safeSample("01_employees");
        assertEquals(
            registry.require("01_employees").generatedSampleSha256(),
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(sample)));
        assertTrue(new String(sample, StandardCharsets.UTF_8)
            .startsWith("template_version,organization_code"));
    }
}

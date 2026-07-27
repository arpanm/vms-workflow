package com.vms.workflow.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceLocalAdaptersTest {
    private final FinanceCanonicalJson canonical =
        new FinanceCanonicalJson(new ObjectMapper());

    @Test
    void localScannerQuarantinesEicarAndExecutableHeaders() {
        LocalFinanceMalwareScanner scanner =
            new LocalFinanceMalwareScanner(true);
        byte[] eicar = (
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!")
            .getBytes(StandardCharsets.US_ASCII);

        assertEquals("QUARANTINED",
            scanner.scan(eicar, "application/pdf", "invoice.pdf").status());
        assertEquals("MALWARE_OR_EXECUTABLE_SIGNATURE",
            scanner.scan(eicar, "application/pdf", "invoice.pdf")
                .reasonCode());
        assertEquals("QUARANTINED",
            scanner.scan(new byte[]{'M', 'Z', 0, 1},
                "application/pdf", "invoice.pdf").status());
        assertEquals("PASSED",
            scanner.scan("%PDF-1.7 safe".getBytes(StandardCharsets.US_ASCII),
                "application/pdf", "invoice.pdf").status());
    }

    @Test
    void scannerUnavailableNeverReturnsAPass() {
        LocalFinanceMalwareScanner scanner =
            new LocalFinanceMalwareScanner(false);
        FinanceMalwareScanner.ScanResult result = scanner.scan(
            "%PDF-1.7 safe".getBytes(StandardCharsets.US_ASCII),
            "application/pdf", "invoice.pdf");

        assertEquals("UNKNOWN", result.status());
        assertEquals("NOT_CONFIGURED", result.engine());
    }

    @Test
    void localRendererProducesAllFormatsAndEscapesFormulaCells()
        throws Exception {
        LocalFinanceReportRenderer renderer =
            new LocalFinanceReportRenderer(canonical);
        Map<String, Object> metadata = Map.of(
            "reportCode", "INVOICE_READINESS",
            "reportVersion", "v1",
            "timezone", "UTC",
            "rowCount", 4);
        List<Map<String, Object>> rows = List.of(
            Map.of("value", "=2+3"),
            Map.of("value", "+cmd"),
            Map.of("value", "-1+2"),
            Map.of("value", "@payload"));

        FinanceReportRenderer.RenderedReport json = renderer.render(
            "INVOICE_READINESS", "v1", "JSON", metadata, rows);
        FinanceReportRenderer.RenderedReport csv = renderer.render(
            "INVOICE_READINESS", "v1", "CSV", metadata, rows);
        FinanceReportRenderer.RenderedReport xlsx = renderer.render(
            "INVOICE_READINESS", "v1", "XLSX", metadata, rows);
        FinanceReportRenderer.RenderedReport pdf = renderer.render(
            "INVOICE_READINESS", "v1", "PDF", metadata, rows);

        assertEquals("application/json", json.mediaType());
        assertTrue(new String(json.content(), StandardCharsets.UTF_8)
            .contains("\"reportVersion\":\"v1\""));
        String csvText = new String(csv.content(), StandardCharsets.UTF_8);
        assertTrue(csvText.contains("'=2+3"));
        assertTrue(csvText.contains("'+cmd"));
        assertTrue(csvText.contains("'-1+2"));
        assertTrue(csvText.contains("'@payload"));
        assertFalse(csvText.contains("\",\"=2+3\""));
        assertArrayEquals(new byte[]{'P', 'K'},
            new byte[]{xlsx.content()[0], xlsx.content()[1]});
        try (ZipInputStream zip = new ZipInputStream(
            new java.io.ByteArrayInputStream(xlsx.content()))) {
            boolean sheetFound = false;
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    String sheet = new String(zip.readAllBytes(),
                        StandardCharsets.UTF_8);
                    assertTrue(sheet.contains("&apos;=2+3"));
                    sheetFound = true;
                }
            }
            assertTrue(sheetFound);
        }
        assertTrue(new String(pdf.content(), StandardCharsets.US_ASCII)
            .startsWith("%PDF-"));
        String pdfText =
            new String(pdf.content(), StandardCharsets.US_ASCII);
        assertTrue(pdfText.contains("metadata.reportCode=INVOICE_READINESS"));
        assertTrue(pdfText.contains("value==2+3"));
        assertTrue(pdfText.contains("value=@payload"));
        assertEquals(64, canonical.sha256Bytes(csv.content()).length());
    }

    @Test
    void rendererBytesAreStableAcrossRenderTimesAndMapInsertionOrder() {
        LocalFinanceReportRenderer renderer =
            new LocalFinanceReportRenderer(canonical);
        Map<String, Object> firstMetadata = new LinkedHashMap<>();
        firstMetadata.put("zeta", "last");
        firstMetadata.put("alpha", "first");
        Map<String, Object> secondMetadata = new LinkedHashMap<>();
        secondMetadata.put("alpha", "first");
        secondMetadata.put("zeta", "last");
        Map<String, Object> firstRow = new LinkedHashMap<>();
        firstRow.put("source", Map.of("version", "v1", "id", "source-1"));
        firstRow.put("logicalType", "INVOICE_DOCUMENT");
        Map<String, Object> secondRow = new LinkedHashMap<>();
        secondRow.put("logicalType", "INVOICE_DOCUMENT");
        secondRow.put("source", Map.of("id", "source-1", "version", "v1"));

        for (String format : List.of("JSON", "PDF", "CSV", "XLSX")) {
            byte[] first = renderer.render(
                "evidence-package", "v2", format,
                firstMetadata, List.of(firstRow)).content();
            byte[] later = renderer.render(
                "evidence-package", "v2", format,
                secondMetadata, List.of(secondRow)).content();
            assertArrayEquals(first, later,
                format + " output must not depend on render time or map order");
        }
    }
}

package com.vms.workflow.application;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Provider-neutral deterministic local renderer for JSON/CSV/PDF/XLSX.
 */
@Component
public class LocalFinanceReportRenderer implements FinanceReportRenderer {
    private final FinanceCanonicalJson canonical;

    public LocalFinanceReportRenderer(FinanceCanonicalJson canonical) {
        this.canonical = canonical;
    }

    @Override
    public RenderedReport render(
        String reportCode,
        String reportVersion,
        String format,
        Map<String, Object> metadata,
        List<Map<String, Object>> rows
    ) {
        String normalized = format.toUpperCase(java.util.Locale.ROOT);
        String base = reportCode.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9-]", "-") + "-" + reportVersion;
        return switch (normalized) {
            case "JSON" -> new RenderedReport(
                canonical.write(Map.of("metadata", metadata, "rows", rows))
                    .getBytes(StandardCharsets.UTF_8),
                "application/json", base + ".json");
            case "CSV" -> new RenderedReport(
                csv(rows).getBytes(StandardCharsets.UTF_8),
                "text/csv", base + ".csv");
            case "XLSX" -> new RenderedReport(
                xlsx(rows), "application/vnd.openxmlformats-officedocument."
                    + "spreadsheetml.sheet", base + ".xlsx");
            case "PDF" -> new RenderedReport(
                pdf(reportCode + " " + reportVersion, metadata, rows),
                "application/pdf", base + ".pdf");
            default -> throw new IllegalArgumentException(
                "Unsupported finance report format.");
        };
    }

    private String csv(List<Map<String, Object>> rows) {
        List<String> headers = orderedColumns(rows);
        StringBuilder result = new StringBuilder();
        result.append(headers.stream().map(this::csvCell)
            .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for (Map<String, Object> row : rows) {
            result.append(headers.stream()
                .map(key -> csvCell(cellValue(row.get(key))))
                .collect(java.util.stream.Collectors.joining(",")))
                .append("\r\n");
        }
        return result.toString();
    }

    private String csvCell(String value) {
        String safe = formulaSafe(value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String formulaSafe(String value) {
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private byte[] xlsx(List<Map<String, Object>> rows) {
        List<String> headers = orderedColumns(rows);
        StringBuilder sheet = new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/"
                + "spreadsheetml/2006/main\"><sheetData>");
        List<List<String>> allRows = new ArrayList<>();
        allRows.add(headers);
        rows.forEach(row -> allRows.add(headers.stream()
            .map(key -> formulaSafe(cellValue(row.get(key))))
            .toList()));
        for (int rowIndex = 0; rowIndex < allRows.size(); rowIndex++) {
            sheet.append("<row r=\"").append(rowIndex + 1).append("\">");
            List<String> values = allRows.get(rowIndex);
            for (int column = 0; column < values.size(); column++) {
                sheet.append("<c r=\"").append(columnName(column))
                    .append(rowIndex + 1)
                    .append("\" t=\"inlineStr\"><is><t>")
                    .append(xml(values.get(column)))
                    .append("</t></is></c>");
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                entry(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/"
                        + "package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/"
                        + "vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/"
                        + "vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/"
                        + "vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                        + "</Types>");
                entry(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                        + "package/2006/relationships\"><Relationship Id=\"rId1\" "
                        + "Type=\"http://schemas.openxmlformats.org/officeDocument/"
                        + "2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                        + "</Relationships>");
                entry(zip, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<workbook xmlns=\"http://schemas.openxmlformats.org/"
                        + "spreadsheetml/2006/main\" xmlns:r=\"http://schemas."
                        + "openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"Report\" sheetId=\"1\" "
                        + "r:id=\"rId1\"/></sheets></workbook>");
                entry(zip, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                        + "package/2006/relationships\"><Relationship Id=\"rId1\" "
                        + "Type=\"http://schemas.openxmlformats.org/officeDocument/"
                        + "2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                        + "</Relationships>");
                entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render XLSX report.", exception);
        }
    }

    private byte[] pdf(
        String title,
        Map<String, Object> metadata,
        List<Map<String, Object>> rows
    ) {
        List<String> lines = new ArrayList<>();
        lines.add(title + " | rows=" + rows.size());
        metadata.keySet().stream().sorted().forEach(key ->
            addWrapped(lines, "metadata." + key + "="
                + cellValue(metadata.get(key))));
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            lines.add("row[" + rowIndex + "]");
            Map<String, Object> row = rows.get(rowIndex);
            row.keySet().stream().sorted().forEach(key ->
                addWrapped(lines, key + "=" + cellValue(row.get(key))));
        }

        int linesPerPage = 55;
        int pageCount = Math.max(
            1, (lines.size() + linesPerPage - 1) / linesPerPage);
        int fontObject = 3 + pageCount * 2;
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int page = 0; page < pageCount; page++) {
            kids.append(3 + page * 2).append(" 0 R ");
        }
        objects.add("<< /Type /Pages /Kids [" + kids + "] /Count "
            + pageCount + " >>");
        for (int page = 0; page < pageCount; page++) {
            int contentObject = 4 + page * 2;
            objects.add("<< /Type /Page /Parent 2 0 R "
                + "/MediaBox [0 0 612 792] /Resources << /Font << /F1 "
                + fontObject + " 0 R >> >> /Contents "
                + contentObject + " 0 R >>");
            int start = page * linesPerPage;
            int end = Math.min(lines.size(), start + linesPerPage);
            StringBuilder content = new StringBuilder(
                "BT /F1 9 Tf 54 760 Td ");
            for (int index = start; index < end; index++) {
                if (index > start) {
                    content.append("0 -12 Td ");
                }
                content.append("(").append(pdfText(lines.get(index)))
                    .append(") Tj ");
            }
            content.append("ET");
            String stream = content.toString();
            objects.add("<< /Length "
                + stream.getBytes(StandardCharsets.US_ASCII).length
                + " >>\nstream\n" + stream + "\nendstream");
        }
        objects.add(
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(pdf.toString().getBytes(StandardCharsets.US_ASCII).length);
            pdf.append(index + 1).append(" 0 obj\n")
                .append(objects.get(index)).append("\nendobj\n");
        }
        int xref = pdf.toString().getBytes(StandardCharsets.US_ASCII).length;
        pdf.append("xref\n0 ").append(objects.size() + 1)
            .append("\n0000000000 65535 f \n");
        offsets.forEach(offset -> pdf.append(String.format("%010d 00000 n \n", offset)));
        pdf.append("trailer << /Size ").append(objects.size() + 1)
            .append(" /Root 1 0 R >>\nstartxref\n").append(xref)
            .append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private void addWrapped(List<String> lines, String value) {
        String remaining = value;
        int max = 95;
        while (remaining.length() > max) {
            int split = remaining.lastIndexOf(' ', max);
            if (split < max / 2) {
                split = max;
            }
            lines.add(remaining.substring(0, split));
            remaining = remaining.substring(split).stripLeading();
        }
        lines.add(remaining);
    }

    private String pdfText(String value) {
        return value.replaceAll("[^\\x20-\\x7E]", "?")
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)");
    }

    private void entry(ZipOutputStream zip, String name, String value)
        throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private List<String> orderedColumns(List<Map<String, Object>> rows) {
        return rows.stream()
            .flatMap(row -> row.keySet().stream())
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private String cellValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return canonical.write(value);
        }
        return String.valueOf(value);
    }

    private String columnName(int index) {
        StringBuilder value = new StringBuilder();
        int current = index;
        do {
            value.insert(0, (char) ('A' + current % 26));
            current = current / 26 - 1;
        } while (current >= 0);
        return value.toString();
    }

    private String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}

package com.vms.workflow.application;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public final class MigrationTemplateWorkbookRenderer {
    public byte[] render(
        MigrationTemplateRegistry.Template template,
        Map<String, List<String>> references
    ) {
        String templateSheet = sheet(List.of(template.headers()));
        List<List<String>> lookupRows = new java.util.ArrayList<>();
        lookupRows.add(List.of("reference_type", "active_code"));
        references.forEach((type, codes) -> codes.forEach(code ->
            lookupRows.add(List.of(type, code))));
        String lookupSheet = sheet(lookupRows);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                entry(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                        + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                        + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                        + "</Types>");
                entry(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                        + "</Relationships>");
                entry(zip, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"Import\" sheetId=\"1\" r:id=\"rId1\"/>"
                        + "<sheet name=\"Active References\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>");
                entry(zip, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                        + "</Relationships>");
                entry(zip, "xl/worksheets/sheet1.xml", templateSheet);
                entry(zip, "xl/worksheets/sheet2.xml", lookupSheet);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to render governed migration workbook.", exception);
        }
    }

    private String sheet(List<List<String>> rows) {
        StringBuilder xml = new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int row = 0; row < rows.size(); row++) {
            xml.append("<row r=\"").append(row + 1).append("\">");
            List<String> values = rows.get(row);
            for (int column = 0; column < values.size(); column++) {
                xml.append("<c r=\"").append(columnName(column))
                    .append(row + 1).append("\" t=\"inlineStr\"><is><t>")
                    .append(xml(values.get(column))).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private String columnName(int index) {
        StringBuilder result = new StringBuilder();
        int value = index + 1;
        while (value > 0) {
            value--;
            result.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return result.toString();
    }

    private String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private void entry(ZipOutputStream zip, String name, String value)
        throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

package com.vms.workflow.application;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

@Component
public final class MigrationCsvParser {
    private static final int MAX_FIELD_CHARS = 100_000;

    public List<Record> parse(Reader input, int maxRows) throws IOException {
        PushbackReader reader = new PushbackReader(input, 1);
        List<Record> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean atFieldStart = true;
        int physicalLine = 1;
        int recordLine = 1;
        int value;
        boolean firstCharacter = true;

        while ((value = reader.read()) != -1) {
            char character = (char) value;
            if (firstCharacter && character == '\ufeff') {
                firstCharacter = false;
                continue;
            }
            firstCharacter = false;
            if (quoted) {
                if (character == '"') {
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) {
                            reader.unread(next);
                        }
                    }
                } else {
                    field.append(character);
                    if (character == '\n') {
                        physicalLine++;
                    }
                }
            } else if (character == '"' && atFieldStart) {
                quoted = true;
                atFieldStart = false;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
                atFieldStart = true;
            } else if (character == '\n' || character == '\r') {
                if (character == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.unread(next);
                    }
                }
                fields.add(field.toString());
                records.add(new Record(recordLine, List.copyOf(fields)));
                if (records.size() > maxRows + 1) {
                    throw new IllegalArgumentException("FILE_ROW_LIMIT_EXCEEDED");
                }
                fields.clear();
                field.setLength(0);
                physicalLine++;
                recordLine = physicalLine;
                atFieldStart = true;
            } else {
                if (!atFieldStart && character == '"') {
                    throw new IllegalArgumentException("FILE_CSV_QUOTE_INVALID");
                }
                field.append(character);
                atFieldStart = false;
            }
            if (field.length() > MAX_FIELD_CHARS) {
                throw new IllegalArgumentException("FIELD_LENGTH_EXCEEDED");
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("FILE_CSV_QUOTE_UNTERMINATED");
        }
        if (!fields.isEmpty() || !field.isEmpty()) {
            fields.add(field.toString());
            records.add(new Record(recordLine, List.copyOf(fields)));
        }
        if (records.isEmpty()) {
            throw new IllegalArgumentException("FILE_EMPTY");
        }
        return List.copyOf(records);
    }

    public record Record(int physicalLine, List<String> fields) {
    }
}

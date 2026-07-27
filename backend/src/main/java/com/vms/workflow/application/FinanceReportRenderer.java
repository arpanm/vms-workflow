package com.vms.workflow.application;

import java.util.List;
import java.util.Map;

public interface FinanceReportRenderer {
    RenderedReport render(
        String reportCode,
        String reportVersion,
        String format,
        Map<String, Object> metadata,
        List<Map<String, Object>> rows
    );

    record RenderedReport(
        byte[] content,
        String mediaType,
        String safeName
    ) {
    }
}

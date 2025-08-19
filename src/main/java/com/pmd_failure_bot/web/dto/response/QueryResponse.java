package com.pmd_failure_bot.web.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class QueryResponse {
    private String llmResponse;
    private List<ReportInfo> reports;

    @Data
    @AllArgsConstructor
    public static class ReportInfo {
        private String path;
        private String workId;
    }
}



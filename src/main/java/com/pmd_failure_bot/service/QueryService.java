package com.pmd_failure_bot.service;

import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private final DatabaseQueryService databaseQueryService;

    @Autowired
    public QueryService(DatabaseQueryService databaseQueryService) {
        this.databaseQueryService = databaseQueryService;
    }

    public QueryResponse processQuery(QueryRequest request) {
        long startTime = System.currentTimeMillis();
        
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new IllegalArgumentException("Query is required");
        }
        
        // Always use natural language processing with function calling
        return processNaturalLanguageQuery(request, startTime);
    }
    
    /**
     * Process natural language queries using function calling and dynamic SQL generation
     */
    private QueryResponse processNaturalLanguageQuery(QueryRequest request, long startTime) {
        try {
            DatabaseQueryService.DatabaseQueryResult result = 
                databaseQueryService.processNaturalLanguageQuery(request.getQuery());
            
            if (result.isSuccessful()) {
                // Convert results to work item links (extract record_id and work_id from results)
                List<QueryResponse.ReportInfo> reportPaths = result.getResults().stream()
                    .map(row -> {
                        String recordId = row.get("record_id") != null ? row.get("record_id").toString() : "N/A";
                        String workId = row.get("work_id") != null ? row.get("work_id").toString() : "N/A";
                        return new QueryResponse.ReportInfo(recordId, workId);
                    })
                    .distinct() // Remove duplicates
                    .collect(Collectors.toList());
                
                long executionTimeMs = System.currentTimeMillis() - startTime;
                
                return new QueryResponse(
                    result.getNaturalLanguageResponse(),
                    reportPaths,
                    LocalDateTime.now(),
                    executionTimeMs
                );
            } else {
                throw new RuntimeException(result.getErrorMessage());
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to process natural language query: " + e.getMessage(), e);
        }
    }
    



} 
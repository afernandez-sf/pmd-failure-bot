package com.pmd_failure_bot.controller;

import com.pmd_failure_bot.dto.QueryRequest;
import com.pmd_failure_bot.dto.QueryResponse;
import com.pmd_failure_bot.service.QueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService queryService;

    @Autowired
    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> processQuery(@RequestBody QueryRequest request) {
        try {
            QueryResponse response = queryService.processQuery(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            QueryResponse errorResponse = new QueryResponse(
                "Error: " + e.getMessage(),
                List.of(),
                LocalDateTime.now(),
                0L
            );
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            QueryResponse errorResponse = new QueryResponse(
                "Internal server error: " + e.getMessage(),
                List.of(),
                LocalDateTime.now(),
                0L
            );
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}
package com.souptik.partitioning.controller;

import com.souptik.partitioning.domain.AuditLogEntity;
import com.souptik.partitioning.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/partitioning/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Range-Partitioned Audit Logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @PostMapping
    @Operation(summary = "Record an audit event (createdAt, the range shard key, is stamped server-side).")
    public ResponseEntity<AuditLogEntity> record(@RequestParam String eventType,
                                                   @RequestParam String actorId,
                                                   @RequestParam String resourceId,
                                                   @RequestParam(defaultValue = "INFO") String severity) {
        AuditLogEntity saved = auditLogService.recordEvent(eventType, actorId, resourceId, severity, Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/window")
    @Operation(summary = "TARGETED range read: bounded createdAt window -> contiguous chunk scan.")
    public ResponseEntity<List<AuditLogEntity>> window(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(auditLogService.findEventsInWindow(from, to));
    }

    @GetMapping("/by-event-type")
    @Operation(summary = "SCATTER-GATHER read: eventType is not the shard key -> broadcast to all shards.")
    public ResponseEntity<List<AuditLogEntity>> byEventType(@RequestParam String eventType) {
        return ResponseEntity.ok(auditLogService.findByEventType(eventType));
    }
}

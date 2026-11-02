package com.smarthome.permission.repository;

import com.smarthome.permission.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByUserId(String userId);
    List<AuditLog> findByOperation(String operation);
    List<AuditLog> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}

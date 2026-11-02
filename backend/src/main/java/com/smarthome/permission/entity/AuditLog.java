package com.smarthome.permission.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "operation", length = 50, nullable = false)
    private String operation;

    @Column(name = "resource_type", length = 30)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "result", length = 20)
    private String result;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

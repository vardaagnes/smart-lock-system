package com.smarthome.permission.service;

import com.smarthome.permission.entity.AuditLog;
import com.smarthome.permission.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public void logOperation(String userId, String operation, String resourceType, String resourceId, String result) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setOperation(operation);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setResult(result);
        auditLogRepository.save(auditLog);
    }

    public void logOperationWithRequest(String userId, String operation, String resourceType, String resourceId, String result, HttpServletRequest request) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setOperation(operation);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setResult(result);
        if (request != null) {
            auditLog.setIpAddress(getClientIpAddress(request));
            auditLog.setUserAgent(request.getHeader("User-Agent"));
        }
        auditLogRepository.save(auditLog);
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getHeader("Proxy-Client-IP");
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }
        return ipAddress;
    }

    public List<AuditLog> getLogsByUser(String userId) {
        return auditLogRepository.findByUserId(userId);
    }

    public List<AuditLog> getLogsByOperation(String operation) {
        return auditLogRepository.findByOperation(operation);
    }

    public List<AuditLog> getLogsByDateRange(LocalDateTime start, LocalDateTime end) {
        return auditLogRepository.findByCreatedAtBetween(start, end);
    }
}

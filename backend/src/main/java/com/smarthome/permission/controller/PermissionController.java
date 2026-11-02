package com.smarthome.permission.controller;

import com.smarthome.permission.dto.PermissionResponse;
import com.smarthome.permission.entity.AuditLog;
import com.smarthome.permission.entity.Device;
import com.smarthome.permission.service.AuditLogService;
import com.smarthome.permission.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final AuditLogService auditLogService;

    /**
     * 获取当前用户有权限的所有设备列表
     */
    @GetMapping("/devices")
    public List<Device> getMyDevices(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        String userId = jwt.getSubject();
        List<Device> devices = permissionService.getDevicesByUser(userId);

        auditLogService.logOperationWithRequest(
                userId,
                "QUERY_DEVICES",
                "DEVICE",
                null,
                "SUCCESS",
                request
        );

        return devices;
    }

    /**
     * 检查当前用户对指定设备的权限
     */
    @GetMapping("/check/{deviceId}")
    public ResponseEntity<PermissionResponse> checkPermission(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable String deviceId,
                                                              HttpServletRequest request) {
        String userId = jwt.getSubject();
        String level = permissionService.getPermissionLevel(userId, deviceId);
        boolean hasPermission = !"NONE".equals(level);

        PermissionResponse response = new PermissionResponse(
                hasPermission,
                level,
                hasPermission ? "允许访问" : "无权限访问该设备"
        );

        auditLogService.logOperationWithRequest(
                userId,
                "CHECK_PERMISSION",
                "DEVICE",
                deviceId,
                hasPermission ? "SUCCESS" : "DENIED",
                request
        );

        return hasPermission ? ResponseEntity.ok(response) : ResponseEntity.status(403).body(response);
    }

    /**
     * 测试接口（无需认证，用于验证服务是否正常运行）
     */
    @GetMapping("/test")
    public String test() {
        return "Permission Service is running!";
    }

    /**
     * 获取当前用户的审计日志
     */
    @GetMapping("/audit")
    public List<AuditLog> getAuditLogs(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return auditLogService.getLogsByUser(userId);
    }
}
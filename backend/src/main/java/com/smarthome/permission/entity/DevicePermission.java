package com.smarthome.permission.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "device_permission")
@IdClass(DevicePermissionId.class)
public class DevicePermission {
    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Id
    @Column(name = "device_id", length = 64, nullable = false)
    private String deviceId;

    @Column(name = "permission_level", length = 20, nullable = false)
    private String permissionLevel; // VIEW, CONTROL, ADMIN

    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt = LocalDateTime.now();
}
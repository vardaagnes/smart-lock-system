package com.smarthome.permission.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "device")
public class Device {
    @Id
    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "device_name", length = 100, nullable = false)
    private String deviceName;

    @Column(name = "gateway_id", length = 64)
    private String gatewayId;

    @Column(name = "group_id", length = 64)
    private String groupId;

    @Column(name = "status", length = 20)
    private String status = "ONLINE";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
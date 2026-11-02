package com.smarthome.permission.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "device_group")
public class DeviceGroup {
    @Id
    @Column(name = "group_id", length = 64)
    private String groupId;

    @Column(name = "group_name", length = 100, nullable = false)
    private String groupName;

    @Column(name = "parent_group_id", length = 64)
    private String parentGroupId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
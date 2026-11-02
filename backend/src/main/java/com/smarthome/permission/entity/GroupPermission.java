package com.smarthome.permission.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "group_permission")
@IdClass(GroupPermissionId.class)
public class GroupPermission {
    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Id
    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "permission_level", length = 20, nullable = false)
    private String permissionLevel; // VIEW, CONTROL, ADMIN

    @Column(name = "granted_by", length = 64)
    private String grantedBy;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt = LocalDateTime.now();
}
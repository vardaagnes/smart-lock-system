package com.smarthome.permission.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupPermissionId implements Serializable {
    private String userId;
    private String groupId;
}
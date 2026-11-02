package com.smarthome.permission.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PermissionResponse {
    private boolean hasPermission;
    private String permissionLevel;
    private String message;
}
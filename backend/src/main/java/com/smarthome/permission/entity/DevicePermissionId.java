package com.smarthome.permission.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DevicePermissionId implements Serializable {
    private String userId;
    private String deviceId;
}
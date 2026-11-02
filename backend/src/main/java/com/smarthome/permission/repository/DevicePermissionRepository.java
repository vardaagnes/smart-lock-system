package com.smarthome.permission.repository;

import com.smarthome.permission.entity.DevicePermission;
import com.smarthome.permission.entity.DevicePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DevicePermissionRepository extends JpaRepository<DevicePermission, DevicePermissionId> {
    List<DevicePermission> findByUserId(String userId);
    Optional<DevicePermission> findByUserIdAndDeviceId(String userId, String deviceId);
}
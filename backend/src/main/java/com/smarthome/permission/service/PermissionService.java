package com.smarthome.permission.service;

import com.smarthome.permission.entity.Device;
import com.smarthome.permission.entity.DevicePermission;
import com.smarthome.permission.repository.DevicePermissionRepository;
import com.smarthome.permission.repository.DeviceRepository;
import com.smarthome.permission.repository.GroupPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final DevicePermissionRepository devicePermissionRepository;
    private final GroupPermissionRepository groupPermissionRepository;
    private final DeviceRepository deviceRepository;

    /**
     * 获取用户有权限的所有设备（包含直接授权和分组授权）
     */
    public List<Device> getDevicesByUser(String userId) {
        // 1. 获取直接授权的设备
        List<DevicePermission> directPermissions = devicePermissionRepository.findByUserId(userId);
        List<String> directDeviceIds = directPermissions.stream()
                .map(DevicePermission::getDeviceId)
                .toList();
        List<Device> directDevices = deviceRepository.findAllById(directDeviceIds);

        // 2. 获取分组授权的设备
        List<Device> groupDevices = deviceRepository.findDevicesByGroupPermission(userId);

        // 3. 合并去重
        Set<Device> allDevices = new HashSet<>(directDevices);
        allDevices.addAll(groupDevices);

        return new ArrayList<>(allDevices);
    }

    /**
     * 获取用户对指定设备的最高权限等级
     * 优先级：直接授权 > 分组授权
     * @return 权限等级 (VIEW, CONTROL, ADMIN) 或 "NONE"
     */
    public String getPermissionLevel(String userId, String deviceId) {
        // 1. 先查直接授权
        return devicePermissionRepository.findByUserIdAndDeviceId(userId, deviceId)
                .map(DevicePermission::getPermissionLevel)
                // 2. 没有直接授权，查分组授权
                .orElseGet(() -> {
                    Device device = deviceRepository.findById(deviceId).orElse(null);
                    if (device == null || device.getGroupId() == null) {
                        return "NONE";
                    }
                    return groupPermissionRepository.findByUserIdAndGroupId(userId, device.getGroupId())
                            .map(gp -> gp.getPermissionLevel())
                            .orElse("NONE");
                });
    }
}
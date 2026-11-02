package com.smarthome.permission.repository;

import com.smarthome.permission.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, String> {

    // 查询用户通过分组授权的所有设备
    @Query("SELECT d FROM Device d JOIN GroupPermission gp ON d.groupId = gp.groupId WHERE gp.userId = :userId")
    List<Device> findDevicesByGroupPermission(@Param("userId") String userId);
}
package com.smarthome.permission.repository;

import com.smarthome.permission.entity.GroupPermission;
import com.smarthome.permission.entity.GroupPermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface GroupPermissionRepository extends JpaRepository<GroupPermission, GroupPermissionId> {
    Optional<GroupPermission> findByUserIdAndGroupId(String userId, String groupId);
}
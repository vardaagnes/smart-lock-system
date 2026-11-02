package com.smarthome.permission.repository;

import com.smarthome.permission.entity.TempTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TempTicketRepository extends JpaRepository<TempTicketEntity, String> {

    /** 按锁查票（联调 / revoke 前校验用） */
    List<TempTicketEntity> findByLockId(String lockId);

    /**
     * 按长期用户查票，用于「按人批量撤销」。
     *
     * <p><b>必须带上 {@code ticketType}，不能只按 {@code userId} 查</b>：
     * 访客票的 {@code user_id} 是空串（实体默认值），只按 userId 查时，
     * 一旦调用方传了空串就会命中全库所有访客票。这里的类型条件是和 Controller 的空白校验并列的第二道锁。
     *
     * <p>不筛 {@code status}：已撤销的也要查出来 —— 幂等要靠它返回当初那次的 rv，
     * 而不是让调用方看到「什么都没发生」。
     */
    List<TempTicketEntity> findByUserIdAndTicketType(String userId, String ticketType);
}

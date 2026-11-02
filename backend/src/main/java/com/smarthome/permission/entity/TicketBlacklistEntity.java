package com.smarthome.permission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * ticket_blacklist 表实体：被撤销的票。
 *
 * <p>为什么不直接在 {@link TempTicketEntity} 上改 status 了事，而要单独一张表：
 * <ol>
 *   <li>撤销是「只增不改的事件流」，票是「对象」。<b>只有事件流能做增量同步</b>——
 *       靠 rv 递增取增量。票表会被清理（过期、用尽），事件流不能丢，
 *       否则锁端永远补不齐撤销记录。</li>
 *   <li>用时间戳当增量游标不可靠（两端时钟会漂），<b>单调递增的整数才是可靠游标</b>。</li>
 * </ol>
 *
 * <p><b>rv（revision）语义 —— 09-30 组长确认：全局单调递增，每撤销一次 +1</b>，不是每张票一个。
 * 锁端存本地 last_rv，上线时网关推最新 rv，锁拉「rv &gt; last_rv」的增量。
 * 改这个语义要锁端、网关一起动，不是权限组自己能改的。
 */
@Entity
@Table(name = "ticket_blacklist")
@Data
public class TicketBlacklistEntity {

    /** 被撤销的票号。PK —— 一张票最多被撤销一次，这层唯一性由主键兜底 */
    @Id
    @Column(name = "t_id", length = 32)
    private String tId;

    /** 票归属的门锁。锁端只同步自己这把锁的撤销记录，不打听别人的 */
    @Column(name = "lock_id", nullable = false, length = 64)
    private String lockId;

    /**
     * 全局递增版本号。
     *
     * <p>唯一约束是刻意的：并发撤销时若两个请求取到同一个 MAX，
     * 会被数据库挡成异常，而不是<b>静默写坏增量游标</b>——后者会让锁端永久漏掉一条撤销，
     * 且症状是「某张票撤不掉」，极难查。
     */
    @Column(name = "rv", nullable = false, unique = true)
    private long rv;

    /** 撤销原因，可空 */
    @Column(name = "reason", length = 128)
    private String reason;

    /** 谁撤的（管理员 JWT 的 sub） */
    @Column(name = "revoked_by", length = 64)
    private String revokedBy;

    /** 撤销时间 */
    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;
}

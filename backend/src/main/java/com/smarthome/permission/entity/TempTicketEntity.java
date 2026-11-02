package com.smarthome.permission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * temp_ticket 表实体（第二轮）。
 *
 * 决定⑨：另建实体类、与协议 DTO TempTicket 分家，理由：
 *  1. TempTicket 是「对外协议」并已冻结，加 @Entity 会拖进 JPA 依赖、还会把 status/createdAt 泄漏到 JSON；
 *  2. 实体列名/长度按库表调，协议字段名按 6.2 冻结，两者演进节奏不同。
 * 注意：PostgreSQL 里 from / to 是关键字，列名必须加引号，否则建表/查询直接报错。
 */
@Entity
@Table(name = "temp_ticket")
@Data
public class TempTicketEntity {

    /** 票号，PK，形如 t-xxxxxxxxxxxx */
    @Id
    @Column(name = "t_id", length = 32)
    private String tId;

    /** 绑定的门锁编号 */
    @Column(name = "lock_id", nullable = false, length = 64)
    private String lockId;

    /** 权限，本阶段固定 open */
    @Column(name = "perm", nullable = false, length = 32)
    private String perm;

    /** 生效时间（Unix 秒） */
    @Column(name = "\"from\"", nullable = false)
    private long from;

    /** 失效时间（Unix 秒） */
    @Column(name = "\"to\"", nullable = false)
    private long to;

    /** 最大使用次数 */
    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    /** Base64(16 字节随机盐) */
    @Column(name = "salt", nullable = false, length = 64)
    private String salt;

    /** SHA256(PIN ‖ salt) 小写十六进制，绝不存 PIN 明文 */
    @Column(name = "pin_hash", nullable = false, length = 64)
    private String pinHash;

    /** 签发时间（Unix 秒） */
    @Column(name = "iat", nullable = false)
    private long iat;

    /**
     * 凭证类型（v2 新增）：longterm / visitor。
     * ⚠️ 给已有数据的表加 NOT NULL 列，PostgreSQL 要求带 DEFAULT —— 先手工 ALTER 再启动，
     * 否则 ddl-auto:update 会因表中有行而报错。见《开发台账》§5 v2 修订说明。
     */
    @Column(name = "ticket_type", nullable = false, length = 16)
    private String ticketType = "visitor";

    /** 长期用户标识（v2 新增）；访客票为空串 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId = "";

    /** 云签名 Base64；M1 定稿前为 PLACEHOLDER_SIG */
    @Column(name = "sig_c", length = 256)
    private String sigC;

    /** 票状态：ACTIVE / REVOKED / EXPIRED / USED_UP */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 入库时间 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

package com.smarthome.permission.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 访客临时票（对应方案设计文档 6.2 的 ticket 结构）。
 *
 * 注意：Java 字段用驼峰，但对外的 JSON 必须与协议一致（snake_case），
 * 所以需要 @JsonProperty 显式指定——Jackson 默认会输出驼峰，网关和锁按协议解析会取不到值。
 */
@Data
public class TempTicket {

    /** 版本号，当前固定 1 */
    @JsonProperty("ver")
    private int ver = 1;

    /** 票号，全局唯一，形如 t-xxxxxxxxxxxx */
    @JsonProperty("t_id")
    private String tId;

    /** 绑定的门锁编号，如 lock-001（一张票不能跨锁使用） */
    @JsonProperty("lock_id")
    private String lockId;

    /** 权限，本阶段固定 "open" */
    @JsonProperty("perm")
    private String perm;

    /** 生效时间（Unix 秒） */
    @JsonProperty("from")
    private long from;

    /** 失效时间（Unix 秒） */
    @JsonProperty("to")
    private long to;

    /** 最大使用次数 */
    @JsonProperty("max_uses")
    private int maxUses;

    /** Base64(16 字节随机盐)，每张票都不同 */
    @JsonProperty("salt")
    private String salt;

    /** SHA256(PIN ‖ salt 原始字节) 的小写十六进制；票里绝不存 PIN 明文 */
    @JsonProperty("pin_hash")
    private String pinHash;

    /** 签发时间（Unix 秒） */
    @JsonProperty("iat")
    private long iat;

    /**
     * 凭证类型（冻结单 v2 / 方案 v0.8 §6.1.5 新增）：
     * "longterm" = 长期用户（固定口令，{@code max_uses=0} 判为不限次）；"visitor" = 访客（临时 PIN）。
     *
     * <p><b>必须进签名</b>——否则可被翻转（把长期票当访客票用），见 {@code TicketService.canonicalString()}。
     */
    @JsonProperty("ticket_type")
    private String ticketType = "visitor";

    /**
     * 长期用户标识（冻结单 v2 新增）；访客票固定为空串 {@code ""}。
     *
     * <p><b>必须进签名</b>——否则可被换人；撤销时可按 {@code user_id} 批量撤。
     */
    @JsonProperty("user_id")
    private String userId = "";

    /** 云签名（DER 格式，Base64 编码），由签名模块填充（本阶段先占位，M1 定稿后接入） */
    @JsonProperty("sig_c")
    private String sigC;
}

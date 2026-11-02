package com.smarthome.permission.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 发放访客临时票请求体（T1：POST /api/ticket/issue）。
 * pin 为访客 PIN 明文，只存在于本次请求中，绝不落库。
 *
 * 字段名显式钉成 snake_case，与接口冻结单「JSON 字段名一律 snake_case」一致
 * （和 TempTicket 同一套做法）。
 *
 * ⚠️ 不要改成全局 Jackson 命名策略（PropertyNamingStrategy.SNAKE_CASE）——
 * 那会把 /api/permission/* 那批既有接口的响应体一起改掉，前端会直接崩。
 */
@Data
public class IssueTicketRequest {

    /** 目标门锁编号，如 lock-001 */
    @JsonProperty("lock_id")
    private String lockId;

    /** 权限，不传时默认 open */
    @JsonProperty("perm")
    private String perm = "open";

    /** 生效时间（Unix 秒） */
    @JsonProperty("from")
    private long from;

    /** 失效时间（Unix 秒） */
    @JsonProperty("to")
    private long to;

    /** 最大使用次数，默认 1 */
    @JsonProperty("max_uses")
    private int maxUses = 1;

    /** 访客 PIN 明文 */
    @JsonProperty("pin")
    private String pin;

    /** 凭证类型（v2 新增）：longterm / visitor，不传默认 visitor */
    @JsonProperty("ticket_type")
    private String ticketType = "visitor";

    /**
     * 长期用户标识（v2 新增）：长期票必填，访客票忽略（服务端强制写成空串）。
     * 注意与 {@code Jwt.getSubject()} 区分——那个是「发起操作的管理员」，这个是「长期用户本人」。
     */
    @JsonProperty("user_id")
    private String userId;
}

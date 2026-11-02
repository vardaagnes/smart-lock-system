package com.smarthome.permission.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 票列表项（GET /api/ticket/list 的响应元素）。
 *
 * <p>按前端列表页要展示的东西挑字段：有效期、次数、状态、是否已撤销（卡 M5 要的
 * 「票 UI 显示有效期 / 次数 / 状态」）。不直接返回实体，理由同 {@link RevokeResult}。
 */
@Data
public class TicketListItem {

    @JsonProperty("t_id")
    private String tId;

    @JsonProperty("lock_id")
    private String lockId;

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

    /** ACTIVE / REVOKED / EXPIRED / USED_UP */
    @JsonProperty("status")
    private String status;

    /** 签发时间（Unix 秒） */
    @JsonProperty("iat")
    private long iat;

    /** 凭证类型（v2）：longterm / visitor —— 列表页据此区分「长期用户」与「访客」 */
    @JsonProperty("ticket_type")
    private String ticketType;

    /** 长期用户标识（v2）；访客票为空串。列表页可显示"谁在用" */
    @JsonProperty("user_id")
    private String userId;

    /** 撤销时的全局版本号；**0 表示未撤销**（rv 从 1 开始，0 是哨兵值） */
    @JsonProperty("rv")
    private long rv;
}

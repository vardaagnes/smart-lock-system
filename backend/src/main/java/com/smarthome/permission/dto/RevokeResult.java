package com.smarthome.permission.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/**
 * 撤销结果（POST /api/ticket/revoke 的响应）。
 *
 * <p>单独建 DTO、不直接返回 {@code TicketBlacklistEntity}：实体是库表形状，
 * 与对外协议分开演进（与 TempTicket / TempTicketEntity 分家同一套理由）。
 *
 * <p><b>rv 必须回给调用方</b> —— 网关靠它知道撤销推进到哪个版本了，进而知道往锁端推什么增量。
 */
@Data
public class RevokeResult {

    @JsonProperty("t_id")
    private String tId;

    @JsonProperty("lock_id")
    private String lockId;

    /** 本次撤销拿到的全局版本号；若这张票之前已撤销，返回的是当初那次的 rv（幂等） */
    @JsonProperty("rv")
    private long rv;

    @JsonProperty("revoked_at")
    private Instant revokedAt;
}

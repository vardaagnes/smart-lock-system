package com.smarthome.permission.controller;

import com.smarthome.permission.dto.CrlResponse;
import com.smarthome.permission.dto.IssueTicketRequest;
import com.smarthome.permission.dto.RevokeBatchResult;
import com.smarthome.permission.dto.RevokeTicketRequest;
import com.smarthome.permission.dto.TicketListItem;
import com.smarthome.permission.entity.TempTicket;
import com.smarthome.permission.entity.TempTicketEntity;
import com.smarthome.permission.repository.TempTicketRepository;
import com.smarthome.permission.service.AuditLogService;
import com.smarthome.permission.service.CrlService;
import com.smarthome.permission.service.TicketRevokeService;
import com.smarthome.permission.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 云签发票接口（T1：发码 → 返回一张票）。
 * 鉴权无需额外配置：SecurityConfig 已 anyRequest().authenticated()，/api/ticket/** 自动要求 JWT。
 */
@RestController
@RequestMapping("/api/ticket")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final TicketRevokeService ticketRevokeService;
    private final CrlService crlService;
    private final TempTicketRepository tempTicketRepository;
    private final AuditLogService auditLogService;

    /** 发放访客临时票（T1 目标接口） */
    @PostMapping("/issue")
    public ResponseEntity<TempTicket> issue(@AuthenticationPrincipal Jwt jwt,
                                            @RequestBody IssueTicketRequest req,
                                            HttpServletRequest request) {
        // operatorId = 发起本次操作的管理员（JWT subject）。
        // 特别注意：票里也有个 user_id，那是「长期用户本人」，两者含义完全不同，故不共用一个变量名——
        // 一旦混用，撤销时按 user_id 批量撤会撤到管理员身上。
        String operatorId = jwt.getSubject();

        TempTicket ticket;
        try {
            // v2：按 ticket_type 分派（visitor / longterm）；未知类型由 TicketService 抛 IllegalArgumentException
            ticket = ticketService.generate(
                    req.getLockId(), req.getPerm(), req.getFrom(), req.getTo(),
                    req.getMaxUses(), req.getPin(), req.getTicketType(), req.getUserId());
        } catch (IllegalArgumentException e) {
            // 只有「请求参数不合法」才走这里（TicketService 内置的参数校验）
            auditLogService.logOperationWithRequest(
                    operatorId, "ISSUE_TICKET", "LOCK", req.getLockId(), "DENIED", request);
            return ResponseEntity.badRequest().build();
        }

        // 落 temp_ticket 表（sig_c 仍为占位符，等 M1 公共加密库定稿后补真签名并回写）。
        // 故意放在 try 外面：save 的异常（库连不上、主键冲突）是服务端错误，
        // 不能被上面那个 catch 吞成 400 + DENIED，否则审计日志会写一条假的拒绝记录，排查时被带偏。
        tempTicketRepository.save(toEntity(ticket));

        auditLogService.logOperationWithRequest(
                operatorId, "ISSUE_TICKET", "LOCK", req.getLockId(), "SUCCESS", request);
        return ResponseEntity.ok(ticket);
    }

    /**
     * 撤销：请求体传 {@code t_id} 撤一张，传 {@code user_id} 撤这个人的全部长期凭证。冻结单 §5.1「撤码」。
     *
     * <p>响应统一成 {@link RevokeBatchResult}（带 {@code results} 数组），单张撤就是长度为 1 的数组 ——
     * 理由见该 DTO 的类注释。
     */
    @PostMapping("/revoke")
    public ResponseEntity<RevokeBatchResult> revoke(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestBody RevokeTicketRequest req,
                                                   HttpServletRequest request) {
        // operatorId = 发起本次操作的管理员。特别别和请求体里的 user_id 混：
        // 后者是「被撤的那个长期用户」，一个是操作者、一个是被操作对象，混用会把管理员自己的票撤掉。
        String operatorId = jwt.getSubject();

        boolean byTicket = req.getTId() != null && !req.getTId().isBlank();
        boolean byUser = req.getUserId() != null && !req.getUserId().isBlank();

        // 必须恰好二选一：
        // 都不给 = 没说要撤什么；都给 = 调用方没想清楚要撤哪一层，替它猜就可能撤掉一整个用户。
        if (byTicket == byUser) {
            auditLogService.logOperationWithRequest(
                    operatorId, "REVOKE_TICKET", "TICKET",
                    byTicket ? req.getTId() : req.getUserId(), "DENIED", request);
            return ResponseEntity.badRequest().build();
        }

        String target = byTicket ? req.getTId() : req.getUserId();

        RevokeBatchResult result;
        try {
            result = byTicket
                    ? ticketRevokeService.revoke(req.getTId(), req.getReason(), operatorId)
                    : ticketRevokeService.revokeByUser(req.getUserId(), req.getReason(), operatorId);
        } catch (IllegalArgumentException e) {
            // 票不存在 / user_id 为空 —— 同 issue：只有参数问题走这里，
            // 库异常不被这个 catch 吞掉，免得审计里写出假的 DENIED 记录
            auditLogService.logOperationWithRequest(
                    operatorId, "REVOKE_TICKET", "TICKET", target, "DENIED", request);
            return ResponseEntity.badRequest().build();
        }

        auditLogService.logOperationWithRequest(
                operatorId, "REVOKE_TICKET", "TICKET", target, "SUCCESS", request);
        return ResponseEntity.ok(result);
    }

    /**
     * 票列表，可选按锁 / 按状态过滤。
     * 用 @RequestParam(value = "lock_id") 而不是靠参数名推断 —— 编译若不开 -parameters 就会取不到。
     */
    @GetMapping("/list")
    public ResponseEntity<List<TicketListItem>> list(
            @RequestParam(value = "lock_id", required = false) String lockId,
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(ticketRevokeService.list(lockId, status));
    }

    /**
     * 拉取撤销名单（CRL），锁上线时带 {@code since_rv} 取增量。冻结单 §5.1 第 1 条。
     *
     * <p><b>调用方不是管理员，是锁</b>（经网关）。所以这条路径除了 Keycloak JWT，
     * 实际还需要一层「网关↔云」的通道认证 —— 那条腿走 APISIX 的双向 TLS，不在本服务里。
     * 眼下 SecurityConfig 只有 JWT 一种鉴权方式，<b>这里先照现有配置要求 JWT，不悄悄放开</b>：
     * 放开一条只读接口看着无害，但它会把「谁都能拉到全量撤销名单」变成既成事实，后面加认证反而要改契约。
     * 网关那条腿怎么接，是联调阶段和网关组一起定的事。
     *
     * <p>参数说明：
     * <ul>
     *   <li>{@code lock_id} 可选。给了只返回该锁的条目（锁端正常用法）；不给返回全量（网关对账用）。</li>
     *   <li>{@code since_rv} 可选，默认 0 = 「什么都没同步过」，与 rv 从 1 开始的约定配套。</li>
     * </ul>
     */
    @GetMapping("/crl")
    public ResponseEntity<CrlResponse> crl(
            @RequestParam(value = "lock_id", required = false) String lockId,
            @RequestParam(value = "since_rv", required = false, defaultValue = "0") long sinceRv) {
        // 负游标是调用方拼错了参数，不是「从 0 开始」的意思 —— 静默当成 0 会掩盖 bug
        if (sinceRv < 0) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(crlService.pull(lockId, sinceRv));
    }

    /** TempTicket（对外协议） → TempTicketEntity（库表） */
    private TempTicketEntity toEntity(TempTicket t) {
        TempTicketEntity e = new TempTicketEntity();
        e.setTId(t.getTId());
        e.setLockId(t.getLockId());
        e.setPerm(t.getPerm());
        e.setFrom(t.getFrom());
        e.setTo(t.getTo());
        e.setMaxUses(t.getMaxUses());
        e.setSalt(t.getSalt());
        e.setPinHash(t.getPinHash());
        e.setIat(t.getIat());
        e.setTicketType(t.getTicketType());
        e.setUserId(t.getUserId());
        e.setSigC(t.getSigC());
        e.setStatus("ACTIVE");
        e.setCreatedAt(Instant.now());
        return e;
    }
}

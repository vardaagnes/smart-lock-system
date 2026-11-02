package com.smarthome.permission.service;

import com.smarthome.permission.dto.RevokeBatchResult;
import com.smarthome.permission.dto.RevokeResult;
import com.smarthome.permission.dto.TicketListItem;
import com.smarthome.permission.entity.TempTicketEntity;
import com.smarthome.permission.entity.TicketBlacklistEntity;
import com.smarthome.permission.repository.TempTicketRepository;
import com.smarthome.permission.repository.TicketBlacklistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 撤销 / 列表（第二轮 revoke + list 骨架）。
 *
 * <p>为什么不把撤销写进 {@link TicketService}：TicketService 是「纯签发」逻辑，
 * 刚通过端到端验签，还能脱离 Spring 单独 new 出来自测；撤销是有状态的库操作，
 * 塞进去会把这两点都破坏掉。<b>刚验通的东西别动。</b>
 *
 * <p>撤销做两件事，且在同一事务里：
 * <ol>
 *   <li>往 ticket_blacklist 追加一条事件（拿到全局递增的 rv）—— 这是给锁端增量同步用的；</li>
 *   <li>把 temp_ticket.status 改成 REVOKED —— 这是给前端列表页显示用的。</li>
 * </ol>
 * 两者的用途不同，不能只做其中一个：只写黑名单则列表页看不出已撤销，
 * 只改 status 则锁端永远收不到撤销记录（锁不看票表）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketRevokeService {

    private final TempTicketRepository tempTicketRepository;
    private final TicketBlacklistRepository ticketBlacklistRepository;

    /**
     * 撤销一张票（冻结单 §5.1「撤码」的 {@code t_id} 分支）。
     *
     * <p><b>幂等</b>：重复撤销同一张票不报错、不推进全局 rv，直接返回当初那次的 rv。
     * 理由——重复点击「撤销」、网络重试都会打到这个接口，若每次都 +1，
     * 全局 rv 会被无意义地推高，锁端要做无用增量；而「幂等」比「报错」对调用方更友好。
     *
     * @param tId      票号
     * @param reason   撤销原因，可空
     * @param operator 操作人（JWT sub）
     * @throws IllegalArgumentException 票不存在时抛出 —— 由 controller 翻成 400
     */
    @Transactional
    public RevokeBatchResult revoke(String tId, String reason, String operator) {
        TempTicketEntity ticket = tempTicketRepository.findById(tId)
                .orElseThrow(() -> new IllegalArgumentException("票不存在: " + tId));

        RevokeBatchResult result = new RevokeBatchResult();
        revokeOne(ticket, reason, operator, result);
        return result;
    }

    /**
     * 按长期用户批量撤销（冻结单 §5.1「撤码」的 {@code user_id} 分支）。
     *
     * <p>用途：某个长期用户的口令泄露了，管理员要一次把<b>这个人</b>的凭证全废掉，
     * 而不是翻列表一张张找。命中的是所有 {@code ticket_type=longterm} 且 {@code user_id} 匹配的票，
     * <b>跨锁</b>——一个人可能同时有多个门的权限，泄露时都得撤。
     *
     * <p><b>每张票各占一个 rv</b>：rv 是「撤销事件流」的序号，不是一个批次号。
     * 一次撤 3 张就推进 3 格，锁端下次按增量拉到 3 条 —— 这与推送时代逐个下发是同一份信息量，
     * 只是换了方向。
     *
     * <p><b>已过期但未撤销的票也会被撤</b>：没有按时效筛。撤销表达的是「这份凭证不再被承认」，
     * 与它是否还能用无关；少一个时间分支，就少一处两端时钟不一致带来的麻烦。
     *
     * @throws IllegalArgumentException {@code userId} 空白时抛出 —— 空串会命中全库访客票
     */
    @Transactional
    public RevokeBatchResult revokeByUser(String userId, String reason, String operator) {
        // 第二道锁（第一道在 Controller）：访客票的 user_id 就是空串，
        // 空串查下去等于「撤掉所有人的访客票」。这里宁可抛异常，也不能静默当成查询条件。
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id 不能为空");
        }

        List<TempTicketEntity> tickets =
                tempTicketRepository.findByUserIdAndTicketType(userId, TicketService.TYPE_LONGTERM);

        RevokeBatchResult result = new RevokeBatchResult();
        result.setUserId(userId);
        for (TempTicketEntity ticket : tickets) {
            revokeOne(ticket, reason, operator, result);
        }

        log.info("按人批量撤销：user_id={}, 命中 {} 张, 本次新撤 {}, by={}",
                userId, tickets.size(), result.getRevokedCount(), operator);
        return result;
    }

    /**
     * 撤一张，结果追加到 {@code out}。<b>幂等与编号分配都在这里，两条入口共用</b>——
     * 单张撤和批量撤各写一遍的话，迟早有一边把「已撤过返回原 rv」漏掉。
     */
    private void revokeOne(TempTicketEntity ticket, String reason, String operator,
                           RevokeBatchResult out) {
        String tId = ticket.getTId();

        // 幂等分支：已经撤过，返回原 rv，不占新号，也不计入 revoked_count
        TicketBlacklistEntity existing = ticketBlacklistRepository.findById(tId).orElse(null);
        if (existing != null) {
            log.info("重复撤销，返回原 rv：t_id={}, rv={}", tId, existing.getRv());
            out.getResults().add(toResult(existing));
            return;
        }

        // lock_id 取自票本身，不信请求体：调用方没机会把票撤到别的锁底下。
        // 请求体里根本没有 lock_id 字段（RevokeTicketRequest 也没有）。
        long nextRv = ticketBlacklistRepository.findMaxRv() + 1;

        TicketBlacklistEntity entry = new TicketBlacklistEntity();
        entry.setTId(tId);
        entry.setLockId(ticket.getLockId());
        entry.setRv(nextRv);
        entry.setReason(reason);
        entry.setRevokedBy(operator);
        entry.setRevokedAt(Instant.now());
        ticketBlacklistRepository.save(entry);

        ticket.setStatus("REVOKED");
        tempTicketRepository.save(ticket);

        log.info("撤销成功：t_id={}, lock_id={}, rv={}, by={}", tId, ticket.getLockId(), nextRv, operator);
        out.getResults().add(toResult(entry));
        out.setRevokedCount(out.getRevokedCount() + 1);
    }

    /**
     * 票列表，可按锁 / 按状态过滤（两个参数都可空）。
     *
     * <p>黑名单用一次 {@code findAllById} 批量查回来做映射，<b>不逐张票查一次</b> ——
     * 后者是典型 N+1，列表页一屏二十张票就是二十条 SQL。
     */
    @Transactional(readOnly = true)
    public List<TicketListItem> list(String lockId, String status) {
        List<TempTicketEntity> tickets = (lockId == null || lockId.isBlank())
                ? tempTicketRepository.findAll()
                : tempTicketRepository.findByLockId(lockId);

        // 状态过滤放在内存里做：票量是本项目规模（管理员手工发码），不值得再给 repository 加一个组合查询方法
        if (status != null && !status.isBlank()) {
            tickets = tickets.stream()
                    .filter(t -> status.equalsIgnoreCase(t.getStatus()))
                    .toList();
        }

        List<String> tIds = tickets.stream().map(TempTicketEntity::getTId).toList();
        Map<String, TicketBlacklistEntity> revoked = tIds.isEmpty()
                ? Map.of()
                : ticketBlacklistRepository.findAllById(tIds).stream()
                        .collect(Collectors.toMap(TicketBlacklistEntity::getTId, Function.identity()));

        return tickets.stream().map(t -> {
            TicketListItem item = new TicketListItem();
            item.setTId(t.getTId());
            item.setLockId(t.getLockId());
            item.setPerm(t.getPerm());
            item.setFrom(t.getFrom());
            item.setTo(t.getTo());
            item.setMaxUses(t.getMaxUses());
            item.setStatus(t.getStatus());
            item.setIat(t.getIat());
            item.setTicketType(t.getTicketType());
            item.setUserId(t.getUserId());
            // 没在黑名单里 = 未撤销，rv 记 0（哨兵值，不是真实版本号）
            TicketBlacklistEntity b = revoked.get(t.getTId());
            item.setRv(b == null ? 0L : b.getRv());
            return item;
        }).toList();
    }

    private RevokeResult toResult(TicketBlacklistEntity e) {
        RevokeResult r = new RevokeResult();
        r.setTId(e.getTId());
        r.setLockId(e.getLockId());
        r.setRv(e.getRv());
        r.setRevokedAt(e.getRevokedAt());
        return r;
    }
}

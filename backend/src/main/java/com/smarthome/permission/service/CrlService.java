package com.smarthome.permission.service;

import com.smarthome.permission.crypto.CloudKeyHolder;
import com.smarthome.permission.crypto.CryptoCommon;
import com.smarthome.permission.dto.CrlResponse;
import com.smarthome.permission.entity.TicketBlacklistEntity;
import com.smarthome.permission.repository.TicketBlacklistRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CRL（撤销名单）拉取。冻结单 §5.1 第 1 条 —— <b>锁 → 网关 → 云</b>，锁带 {@code since_rv} 取增量。
 *
 * <p><b>为什么单开一个类，不塞进 {@link TicketRevokeService}</b>：
 * <ul>
 *   <li>撤销是「写」，CRL 是「只读」；两者事务属性、调用方、出错后果都不同 ——
 *       撤销写坏要回滚，CRL 读失败重试即可；</li>
 *   <li>更重要的是 <b>签名串在这里</b>。票的 canonical 串在 TicketService，CRL 的 canonical 串在这里，
 *       两套契约各自集中在一处，改的时候不会漏；</li>
 *   <li>{@link TicketRevokeService} 没有也不必持有 SK_c —— 撤销本身不需要签名，
 *       为它注入私钥等于扩大一把私钥的暴露面。</li>
 * </ul>
 *
 * <p><b>为什么必须签名</b>：撤销名单是「减法」。网关或链路上任何一环把名单改成空，
 * 锁就会照常开门 —— 而这恰恰是最需要防的场景。签名让「名单为空」也成为一个不可伪造的断言。
 */
@Service
@RequiredArgsConstructor
public class CrlService {

    private static final Logger log = LoggerFactory.getLogger(CrlService.class);

    /**
     * canonical 串格式版本。它是协议常量，<b>不放在响应体里</b>
     * —— 冻结单 §5.1 定的响应字段就是 {@code {rv, entries[], sig_c}}，不另外加字段。
     * 锁端拼串时按 {@code ver=1} 写死。
     */
    private static final String CRL_VER = "1";

    /** 没配 SK_c 时的占位符。锁端验不过 —— 这是刻意的失败姿态，不是故障 */
    private static final String SIG_PLACEHOLDER = "PLACEHOLDER_SIG";

    private final TicketBlacklistRepository blacklistRepository;
    private final CloudKeyHolder keyHolder;

    /**
     * 拉取增量。
     *
     * @param lockId  锁编号；<b>可空</b>。给了就只返回这把锁的条目（锁端正常用法），
     *                不给则返回全量（网关对账用）
     * @param sinceRv 锁本地已知的 last_rv；首次拉取传 0（0 是「什么都没同步过」的哨兵值）
     * @return 带签名 {@code sig_c} 的响应
     */
    @Transactional(readOnly = true)
    public CrlResponse pull(String lockId, long sinceRv) {
        // rv 取全局最大：游标是整个事件流的进度，不能按过滤后的条目算（见 CrlResponse 类注释）
        long currentRv = blacklistRepository.findMaxRv();

        // 两条查询都是 OrderByRvAsc —— 升序是签名串的一部分，不能在内存里再排一次碰运气
        List<TicketBlacklistEntity> rows = (lockId == null || lockId.isBlank())
                ? blacklistRepository.findByRvGreaterThanOrderByRvAsc(sinceRv)
                : blacklistRepository.findByLockIdAndRvGreaterThanOrderByRvAsc(lockId, sinceRv);

        CrlResponse resp = new CrlResponse();
        resp.setLockId(lockId == null ? "" : lockId);
        resp.setRv(currentRv);
        resp.setEntries(rows.stream()
                .map(r -> new CrlResponse.Entry(r.getTId(), r.getRv()))
                .collect(Collectors.toList()));
        // 签名必须在 entries / rv / lock_id 全部填完之后 —— 先签再填等于签了个空
        resp.setSigC(sign(resp));

        log.debug("CRL 下发：lock_id='{}', since_rv={}, 本次 {} 条, rv={}",
                resp.getLockId(), sinceRv, resp.getEntries().size(), currentRv);
        return resp;
    }

    /**
     * 对整份响应签名。
     *
     * <p>没配 SK_c 时回落到占位符而不是抛异常，与 {@code TicketService.signTicket} 同一套理由：
     * 让开发期不配密钥也能把链路跑起来。锁端拿到占位签名会验不过并拒绝整份名单 —— 失败方向是安全的。
     */
    public String sign(CrlResponse resp) {
        if (keyHolder == null || !keyHolder.isReady()) {
            return SIG_PLACEHOLDER;
        }
        String canonical = canonicalString(resp.getLockId(), resp.getRv(), resp.getEntries());
        // 打出来：跨端验签失败时第一个要对比的就是「两端拼出的串是否逐字节相同」。
        // 这串不含任何秘密 —— 内容都在响应体里。上生产前降成 debug。
        log.info("待签名 CRL canonical：{}", canonical);
        return CryptoCommon.sign(canonical, keyHolder.getPrivateKey());
    }

    /**
     * CRL 的待签名内容。锁端必须用<b>完全相同的规则</b>拼出这一串再验签。
     *
     * <pre>
     * ver=1&amp;lock_id=&lt;lockId&gt;&amp;rv=&lt;全局rv&gt;&amp;entries=&lt;t_id&gt;:&lt;rv&gt;,&lt;t_id&gt;:&lt;rv&gt;
     * </pre>
     *
     * <p>三条规则，缺一条两端就对不上：
     * <ol>
     *   <li>entries 按 rv <b>升序</b>，{@code t_id:rv} 逗号分隔，无空格；</li>
     *   <li><b>空列表时保留 {@code &amp;entries=} 这个空尾巴，不得省略</b> ——
     *       省略了就等于允许把整段剥掉，「伪造撤销名单为空」正好是这条签名要防的事；</li>
     *   <li>lock_id 进签名 —— 否则 A 的空名单可以被重放给 B（见 {@link CrlResponse} 类注释）。</li>
     * </ol>
     */
    public static String canonicalString(String lockId, long rv, List<CrlResponse.Entry> entries) {
        return "ver=" + CRL_VER
                + "&lock_id=" + (lockId == null ? "" : lockId)
                + "&rv=" + rv
                + "&entries=" + entriesString(entries);
    }

    /** entries 段：{@code t_id:rv} 逗号分隔，升序；空列表返回空串（调用方负责保留 {@code entries=} 前缀） */
    public static String entriesString(List<CrlResponse.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(entries.get(i).getTId()).append(':').append(entries.get(i).getRv());
        }
        return sb.toString();
    }
}

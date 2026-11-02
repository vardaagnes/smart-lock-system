package com.smarthome.permission.service;

import com.smarthome.permission.crypto.CloudKeyHolder;
import com.smarthome.permission.crypto.CryptoCommon;
import com.smarthome.permission.entity.TempTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 云签发：开锁凭证的核心逻辑（任务Ⅲ · 卡 B）。
 * 组装票据 + 算 PIN 哈希 + 产出待签名内容 + 用 SK_c 签真名。
 *
 * <p><b>v2（10-14，方案 v0.8 §6.1.5）</b>：支持两类凭证 —— 访客票（临时 PIN）与长期用户票（固定口令），
 * 二者<b>共用同一套票结构、同一套锁端五步验签</b>，仅 {@code ticket_type} / {@code user_id} / {@code max_uses} 取值不同。
 *
 * <p>未配置 SK_c 时 {@code sig_c} 回落到占位符（见 {@link CloudKeyHolder}），不阻塞开发。
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 没有可用 SK_c 时的占位签名。锁端一律验不过 —— 只用于开发期把链路跑通 */
    private static final String SIG_PLACEHOLDER = "PLACEHOLDER_SIG";

    /**
     * 云侧私钥持有者。可以是 null —— 脱离 Spring 直接 {@code new TicketService()} 自测时就是 null，
     * 所以这里用 setter 注入 + {@code required = false}，而不是构造器注入。
     */
    private CloudKeyHolder keyHolder;

    @Autowired(required = false)
    public void setKeyHolder(CloudKeyHolder keyHolder) {
        this.keyHolder = keyHolder;
    }

    /** 凭证类型：访客（临时 PIN）。见冻结单 v2 / 方案 v0.8 §6.1.5 */
    public static final String TYPE_VISITOR = "visitor";

    /** 凭证类型：长期用户（固定口令）。此类票 {@code max_uses} 固定为 0 = 不限次 */
    public static final String TYPE_LONGTERM = "longterm";

    /**
     * 签发入口（v2）：按 {@code ticketType} 分派到访客票 / 长期票。
     *
     * <p>未知（含显式传 null）的 {@code ticketType} 一律按参数不合法拒绝 —— 与 {@code perm} 同一思路：
     * 请求体显式传 null 会覆盖 DTO 默认值，若静默按访客票签出去，长期用户会拿到一张只能用一次的废票。
     */
    public TempTicket generate(String lockId, String perm, long from, long to,
                               int maxUses, String pin, String ticketType, String longTermUserId) {
        if (TYPE_LONGTERM.equals(ticketType)) {
            return generateLongTermTicket(lockId, perm, from, to, longTermUserId, pin);
        }
        if (TYPE_VISITOR.equals(ticketType)) {
            return generateTicket(lockId, perm, from, to, maxUses, pin);
        }
        throw new IllegalArgumentException("ticket_type 只能是 " + TYPE_LONGTERM + " 或 " + TYPE_VISITOR);
    }

    /**
     * 生成一张访客临时票。v1 的链路一字未动，只是把组装部分抽到 {@link #assemble}。
     *
     * @param lockId  目标门锁编号，如 lock-001
     * @param perm    权限，本阶段固定 "open"
     * @param from    生效时间（Unix 秒）
     * @param to      失效时间（Unix 秒）
     * @param maxUses 最大使用次数，访客票必须大于 0
     * @param pin     访客 PIN 明文（只用于计算哈希，绝不落库、绝不进票）
     */
    public TempTicket generateTicket(String lockId, String perm, long from, long to, int maxUses, String pin) {
        if (maxUses <= 0) {
            throw new IllegalArgumentException("使用次数必须大于 0");
        }
        return assemble(lockId, perm, from, to, maxUses, pin, TYPE_VISITOR, "");
    }

    /**
     * 生成一张长期用户票（v2 / 方案 v0.8 §6.1.5）。
     *
     * <p>与访客票共用同一套票结构与锁端五步验签，仅参数不同：{@code max_uses} 固定 0（不限次）、
     * {@code user_id} 必填（撤销时可按人批量撤）。
     *
     * <p>注意「不限次」<b>不等于不过期</b>：仍须由调用方给出 {@code to}（如一年后），到期前续签。
     *
     * @param longTermUserId 长期用户<b>本人</b>标识——不是发起操作的管理员
     */
    public TempTicket generateLongTermTicket(String lockId, String perm, long from, long to,
                                             String longTermUserId, String pin) {
        if (longTermUserId == null || longTermUserId.isBlank()) {
            throw new IllegalArgumentException("长期用户票必须指定 user_id");
        }
        // max_uses=0 是「不限次」哨兵，锁端据此判定；不要用 -1 之类的负数
        return assemble(lockId, perm, from, to, 0, pin, TYPE_LONGTERM, longTermUserId);
    }

    /** 两类票共用的组装：参数校验 + 随机盐 + 逐字段填充 + 签名。 */
    private TempTicket assemble(String lockId, String perm, long from, long to, int maxUses,
                                String pin, String ticketType, String userId) {
        if (lockId == null || lockId.isBlank()) {
            throw new IllegalArgumentException("lockId 不能为空");
        }
        if (perm == null || perm.isBlank()) {
            // 必须校验：请求体显式传 perm=null 会覆盖 DTO 的默认值，
            // 不拦的话票里 perm 为空、canonical 串变成 perm=null，签名照样能签过，锁端拿到废票。
            throw new IllegalArgumentException("perm 不能为空");
        }
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("PIN 不能为空");
        }
        if (from >= to) {
            throw new IllegalArgumentException("生效时间必须早于失效时间");
        }

        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);

        TempTicket ticket = new TempTicket();
        ticket.setVer(1);
        ticket.setTId("t-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        ticket.setLockId(lockId);
        ticket.setPerm(perm);
        ticket.setFrom(from);
        ticket.setTo(to);
        ticket.setMaxUses(maxUses);
        ticket.setSalt(Base64.getEncoder().encodeToString(saltBytes));
        ticket.setPinHash(pinHash(pin, saltBytes));
        ticket.setIat(System.currentTimeMillis() / 1000);
        ticket.setTicketType(ticketType);
        ticket.setUserId(userId);
        // 签名必须在所有字段填完之后——canonical 串覆盖全部字段，先签再填等于签了个空
        ticket.setSigC(signTicket(ticket));
        return ticket;
    }

    /**
     * 给票签真名。
     *
     * <p>必须在票的<b>所有字段都填完之后</b>再调 —— canonical 串包含全部字段，先签再填字段等于签了个空。
     *
     * <p>没配 SK_c 时回落到占位符而不是抛异常：让开发期不配密钥也能把整条链路跑通。
     */
    private String signTicket(TempTicket ticket) {
        if (keyHolder == null || !keyHolder.isReady()) {
            return SIG_PLACEHOLDER;
        }
        String canonical = canonicalString(ticket);
        // 把待签名内容打出来：跨端验签失败时，第一个要对比的就是「两端拼出的串是否逐字节相同」。
        // 它本身不含秘密 —— 这些字段在响应体里本来就有。上生产前降成 debug。
        log.info("待签名 canonical：{}", canonical);
        return CryptoCommon.sign(canonical, keyHolder.getPrivateKey());
    }

    /**
     * 校验 PIN 是否匹配该票（自检用；锁端也用同样算法复算）。
     */
    public boolean matchesPin(TempTicket ticket, String pin) {
        byte[] saltBytes = Base64.getDecoder().decode(ticket.getSalt());
        return pinHash(pin, saltBytes).equals(ticket.getPinHash());
    }

    /**
     * 待签名内容：字段顺序固定、用 & 分隔的 k=v 串。
     * 用 & 作分隔是安全的——票里所有字段（编号、Base64、十六进制）都不可能出现 & 字符。
     * 锁端必须用完全相同的规则拼出这一串再验签。
     *
     * <p><b>冻结单 v2（10-14）：10 字段 → 12 字段</b>，新字段 {@code ticket_type} / {@code user_id}
     * <b>追加在末尾</b>（v1 的前缀一字不动），两端同步改完才能互验 —— 详见《开发台账》§5.2。
     */
    public String canonicalString(TempTicket t) {
        return "ver=" + t.getVer()
                + "&t_id=" + t.getTId()
                + "&lock_id=" + t.getLockId()
                + "&perm=" + t.getPerm()
                + "&from=" + t.getFrom()
                + "&to=" + t.getTo()
                + "&max_uses=" + t.getMaxUses()
                + "&salt=" + t.getSalt()
                + "&pin_hash=" + t.getPinHash()
                + "&iat=" + t.getIat()
                + "&ticket_type=" + t.getTicketType()
                + "&user_id=" + t.getUserId();
    }

    /** pin_hash = SHA256(PIN 的 UTF-8 字节 ‖ salt 原始字节) 的小写十六进制 */
    private String pinHash(String pin, byte[] saltBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(pin.getBytes(StandardCharsets.UTF_8));
            digest.update(saltBytes);
            return toHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("计算 PIN 哈希失败", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

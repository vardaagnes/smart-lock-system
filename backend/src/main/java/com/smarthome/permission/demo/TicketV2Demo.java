package com.smarthome.permission.demo;

import com.smarthome.permission.crypto.CloudKeyHolder;
import com.smarthome.permission.entity.TempTicket;
import com.smarthome.permission.service.TicketService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * v2 契约（冻结单 v2 · 方案 v0.8 §6.1.5）的跨端验证入口。
 *
 * <p><b>它跟 {@link TicketDemo} 的区别</b>：TicketDemo 不加载 SK_c，签出来是占位符，
 * 只能看字段对不对；这个类<b>用真实 SK_c 签真名</b>，产出可直接交给 Python 侧独立验签的票，
 * 用来证明「长期用户票与访客票共用同一套验签、锁端不需要第二套逻辑」这句话是真的。
 *
 * <p>产出两个文件（默认落在当前工作目录）：
 * <ul>
 *   <li>{@code ticket-v2-visitor.json} —— 访客票，{@code ticket_type=visitor}、{@code max_uses=1}、{@code user_id=""}</li>
 *   <li>{@code ticket-v2-longterm.json} —— 长期票，{@code ticket_type=longterm}、{@code max_uses=0}、{@code user_id=u-zhangsan}</li>
 * </ul>
 * 然后跑：{@code python verify_ticket.py ticket-v2-visitor.json}（长期票同理）。
 *
 * <p>运行方式（工程根目录）：
 * <pre>
 * mvn -o -q compile
 * java -cp target/classes com.smarthome.permission.demo.TicketV2Demo
 * </pre>
 *
 * <p><b>注意这不是在测 Spring 接口</b> —— 它绕过 Controller 直接调 TicketService。
 * 走 HTTP 的版本（含鉴权、落库、审计）在 M4 全链路演示里做，两者不互相替代。
 */
public class TicketV2Demo {

    private static final String LOCK_ID = "lock-001";

    /** 访客 PIN 与长期用户固定口令 —— 只在本进程内使用，算完哈希即丢，不落任何文件 */
    private static final String VISITOR_PIN = "864275";
    private static final String LONGTERM_PIN = "135790";

    public static void main(String[] args) throws Exception {
        String skcPath = args.length > 0 ? args[0] : "secrets/sk_c.pem";

        // 复用 Spring 那套加载逻辑，不是另抄一遍 PEM 解析
        CloudKeyHolder holder = new CloudKeyHolder(skcPath);
        holder.load();
        if (!holder.isReady()) {
            // 没有真 SK_c 就退出，不产文件 —— 占位签名签出来的票交给 Python 验只会误导人
            throw new IllegalStateException(
                    "SK_c 没加载上（" + skcPath + "）——跑不了跨端验签。先确认 secrets/sk_c.pem 在位。");
        }

        TicketService service = new TicketService();
        service.setKeyHolder(holder);

        long now = System.currentTimeMillis() / 1000;

        // 访客票：2 小时、1 次
        TempTicket visitor = service.generateTicket(
                LOCK_ID, "open", now, now + 2 * 60 * 60, 1, VISITOR_PIN);

        // 长期票：1 年、不限次（max_uses=0 哨兵）。
        // 「不限次」不等于不过期 —— 仍然给了到期日，见方案 v0.8 §6.6「锁长期离线」行
        TempTicket longTerm = service.generateLongTermTicket(
                LOCK_ID, "open", now, now + 365L * 24 * 60 * 60, "u-zhangsan", LONGTERM_PIN);

        write("ticket-v2-visitor.json", visitor, service, VISITOR_PIN);
        write("ticket-v2-longterm.json", longTerm, service, LONGTERM_PIN);
    }

    /** 落一张票 + 打印给人看的核对信息 */
    private static void write(String fileName, TempTicket t, TicketService service, String pin)
            throws Exception {
        String json = toJson(t);

        System.out.println("========== " + fileName + " ==========");
        System.out.println("canonical (12 字段):");
        System.out.println("  " + service.canonicalString(t));
        System.out.println("PIN 正确时复算一致 = " + service.matchesPin(t, pin));
        System.out.println("PIN 错误时复算一致 = " + service.matchesPin(t, "000000"));
        System.out.println("sig_c 长度        = " + t.getSigC().length() + " (Base64)");
        System.out.println();

        Files.writeString(Path.of(fileName), json, StandardCharsets.UTF_8);
        System.out.println("已写出：" + Path.of(fileName).toAbsolutePath());
        System.out.println();
    }

    /**
     * 手写 JSON，不引 Jackson —— 只为让这个 demo 只依赖工程自己的类，
     * {@code java -cp target/classes} 就能跑，不用再拼依赖 classpath。
     * 票里所有字段（编号 / Base64 / 十六进制 / 我写死的 user_id）都不含引号与反斜杠，故不做转义。
     */
    private static String toJson(TempTicket t) {
        return "{\"ver\":" + t.getVer()
                + ",\"t_id\":\"" + t.getTId() + "\""
                + ",\"lock_id\":\"" + t.getLockId() + "\""
                + ",\"perm\":\"" + t.getPerm() + "\""
                + ",\"from\":" + t.getFrom()
                + ",\"to\":" + t.getTo()
                + ",\"max_uses\":" + t.getMaxUses()
                + ",\"salt\":\"" + t.getSalt() + "\""
                + ",\"pin_hash\":\"" + t.getPinHash() + "\""
                + ",\"iat\":" + t.getIat()
                + ",\"ticket_type\":\"" + t.getTicketType() + "\""
                + ",\"user_id\":\"" + t.getUserId() + "\""
                + ",\"sig_c\":\"" + t.getSigC() + "\""
                + "}";
    }
}

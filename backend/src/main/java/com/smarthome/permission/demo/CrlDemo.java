package com.smarthome.permission.demo;

import com.smarthome.permission.crypto.CloudKeyHolder;
import com.smarthome.permission.dto.CrlResponse;
import com.smarthome.permission.service.CrlService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CRL 签名串（冻结单 §5.2 · 10-19 定稿）的跨端验证入口。
 *
 * <p><b>为什么要有这个 demo</b>：CRL 的待签名串是这次才定下来的，锁端（Python）要照着独立实现一遍。
 * 只在 Java 里自签自验等于同一份代码验证自己，证明不了协议能被另一端实现 ——
 * 做法与 {@link TicketV2Demo} 一致：Java 用真 SK_c 签，Python 只用 PK_c 独立验。
 *
 * <p>产出两个文件（默认落在当前工作目录）：
 * <ul>
 *   <li>{@code crl-two-entries.json} —— 两条撤销记录，验证常规增量；</li>
 *   <li>{@code crl-empty.json} —— <b>空名单</b>。这条最关键：空名单也必须签，
 *       否则「撤销名单为空」就成了任何人都能声称的一句话。</li>
 * </ul>
 * 然后跑：{@code python verify_crl.py crl-two-entries.json}
 *
 * <p>运行方式（工程根目录）：
 * <pre>
 * mvn -o -q compile
 * java -cp target/classes com.smarthome.permission.demo.CrlDemo
 * </pre>
 *
 * <p><b>注意这不是在测 Spring 接口</b> —— 它绕过 Controller 与数据库，直接调 {@link CrlService#sign}。
 * 走 HTTP + 真实库的版本要等网关那条腿接通后联调，两者不互相替代。
 */
public class CrlDemo {

    private static final String LOCK_ID = "lock-001";

    public static void main(String[] args) throws Exception {
        String skcPath = args.length > 0 ? args[0] : "secrets/sk_c.pem";

        // 复用 Spring 那套加载逻辑，不是另抄一遍 PEM 解析
        CloudKeyHolder holder = new CloudKeyHolder(skcPath);
        holder.load();
        if (!holder.isReady()) {
            // 没有真 SK_c 就退出，不产文件 —— 占位签名签出来的东西交给 Python 验只会误导人
            throw new IllegalStateException(
                    "SK_c 没加载上（" + skcPath + "）——跑不了跨端验签。先确认 secrets/sk_c.pem 在位。");
        }

        // 第一个参数传 null：这条 demo 不碰数据库，只验签名串。
        // 真实拉取路径是 CrlService#pull，它才需要 repository。
        CrlService service = new CrlService(null, holder);

        // 有两条撤销记录的增量，按 rv 升序（与 repository 的 OrderByRvAsc 一致）
        List<CrlResponse.Entry> two = new ArrayList<>();
        two.add(new CrlResponse.Entry("t-3f8a91c2d7e4", 12));
        two.add(new CrlResponse.Entry("t-b05e6a4419cc", 15));
        write("crl-two-entries.json", service, LOCK_ID, 15L, two);

        // 空名单。rv 仍要给（全局游标照常前进），entries 留空
        write("crl-empty.json", service, LOCK_ID, 15L, new ArrayList<>());
    }

    /** 落一份响应 + 打印给人看的核对信息 */
    private static void write(String fileName, CrlService service, String lockId, long rv,
                              List<CrlResponse.Entry> entries) throws Exception {
        CrlResponse resp = new CrlResponse();
        resp.setLockId(lockId);
        resp.setRv(rv);
        resp.setEntries(entries);
        resp.setSigC(service.sign(resp));

        String canonical = CrlService.canonicalString(lockId, rv, entries);

        System.out.println("========== " + fileName + " ==========");
        System.out.println("canonical : " + canonical);
        // 空名单这一行的尾巴必须是 "entries="，不能干脆没有这一段 —— 见 CrlService#canonicalString
        System.out.println("条目数    = " + entries.size());
        System.out.println("sig_c 长度 = " + resp.getSigC().length() + " (Base64)");
        System.out.println();

        Files.writeString(Path.of(fileName), toJson(resp), StandardCharsets.UTF_8);
        System.out.println("已写出：" + Path.of(fileName).toAbsolutePath());
        System.out.println();
    }

    /**
     * 手写 JSON，不引 Jackson —— 让这个 demo 只依赖工程自己的类，
     * {@code java -cp target/classes} 就能跑。
     * 这里所有字段（编号 / Base64）都不含引号与反斜杠，故不做转义。
     */
    private static String toJson(CrlResponse resp) {
        StringBuilder entries = new StringBuilder("[");
        for (int i = 0; i < resp.getEntries().size(); i++) {
            if (i > 0) {
                entries.append(',');
            }
            CrlResponse.Entry e = resp.getEntries().get(i);
            entries.append("{\"t_id\":\"").append(e.getTId())
                    .append("\",\"rv\":").append(e.getRv()).append('}');
        }
        entries.append(']');

        return "{\"lock_id\":\"" + resp.getLockId() + "\""
                + ",\"rv\":" + resp.getRv()
                + ",\"entries\":" + entries
                + ",\"sig_c\":\"" + resp.getSigC() + "\""
                + "}";
    }
}

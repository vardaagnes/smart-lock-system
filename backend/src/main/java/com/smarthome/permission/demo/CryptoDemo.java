package com.smarthome.permission.demo;

import com.smarthome.permission.crypto.CryptoCommon;
import com.smarthome.permission.entity.TempTicket;
import com.smarthome.permission.service.TicketService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

/**
 * M1 自测：假票 → 真签 → 自验 + 篡改验。
 *
 * <p>跑这个类会打印一段「交给李浩做跨语言往返」的素材 —— 把输出原样发给他，
 * 他那边用 Python 的 crypto_common.py 验一遍，两边都过就是 M1 的验收线达成。
 *
 * <p>运行（在工程根目录，classpath 见编译时的用法）：
 * <pre>
 * java -cp "target/classes;&lt;依赖 jar&gt;" com.smarthome.permission.demo.CryptoDemo
 * </pre>
 */
public class CryptoDemo {

    public static void main(String[] args) {
        // 1) 云侧密钥对：SK_c 自己留着签票，PK_c 交给锁端预装
        KeyPair cloudKeyPair = CryptoCommon.generateKeyPair();

        // 2) 造一张票（不落库，纯内存），拿到待签名内容
        TicketService ticketService = new TicketService();
        TempTicket ticket = ticketService.generateTicket(
                "lock-001", "open",
                System.currentTimeMillis() / 1000,
                System.currentTimeMillis() / 1000 + 7200,
                1, "864275");
        String canonical = ticketService.canonicalString(ticket);

        // 3) 签名 —— 这一步就是将来要替换 TicketService 里 PLACEHOLDER_SIG 的地方
        String sigC = CryptoCommon.sign(canonical, cloudKeyPair.getPrivate());
        ticket.setSigC(sigC);

        // 4) 自验：应该过
        boolean selfCheck = CryptoCommon.verify(canonical, sigC, cloudKeyPair.getPublic());

        // 5) 篡改验：把 max_uses 从 1 改成 99，签名不变 —— 应该验不过
        String tampered = canonical.replace("max_uses=1", "max_uses=99");
        boolean tamperCheck = CryptoCommon.verify(tampered, sigC, cloudKeyPair.getPublic());

        System.out.println("================ M1 Java 侧签名自测 ================");
        System.out.println("待签名内容 canonical 串：");
        System.out.println(canonical);
        System.out.println();
        System.out.println("sig_c（Base64(DER)）：");
        System.out.println(sigC);
        System.out.println();
        System.out.println("自验（应当为 true）：  " + selfCheck);
        System.out.println("篡改后验（应当为 false）：" + tamperCheck);
        System.out.println();
        System.out.println("--- 下面两行是给李浩的 Python 侧用的公钥，两种格式 ---");
        System.out.println("PK_c · SPKI Base64（约 124 字符）：");
        System.out.println(CryptoCommon.publicKeyToSpkiBase64(cloudKeyPair.getPublic()));
        System.out.println("PK_c · 65 字节非压缩点 Base64（88 字符）：");
        System.out.println(CryptoCommon.publicKeyToRawBase64(cloudKeyPair.getPublic()));
        System.out.println("===================================================");

        // 6) 把这一组数据落成 JSON，给 Python 侧做跨语言验签用。
        //    这是 M1 验收的输入 —— 谁写的 Python 库，都拿这个文件验。
        String outPath = args.length > 0 ? args[0] : "m1-roundtrip.json";
        String json = "{\n"
                + "  \"canonical\": " + quote(canonical) + ",\n"
                + "  \"sig_c\": " + quote(sigC) + ",\n"
                + "  \"pk_c_spki_b64\": " + quote(CryptoCommon.publicKeyToSpkiBase64(cloudKeyPair.getPublic())) + ",\n"
                + "  \"pk_c_raw65_b64\": " + quote(CryptoCommon.publicKeyToRawBase64(cloudKeyPair.getPublic())) + ",\n"
                + "  \"tampered_max_uses_99\": " + quote(tampered) + "\n"
                + "}\n";
        try {
            Files.write(Path.of(outPath), json.getBytes(StandardCharsets.UTF_8));
            System.out.println("已写出跨语言验签素材：" + Path.of(outPath).toAbsolutePath());
        } catch (Exception e) {
            System.out.println("写素材文件失败：" + e.getMessage());
        }
    }

    /** 最小的 JSON 字符串转义（只处理我们自己的字段，够用） */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

package com.smarthome.permission.demo;

import com.smarthome.permission.crypto.CryptoCommon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

/**
 * 生成云侧签名密钥对 SK_c / PK_c。
 *
 * <p><b>整个项目周期只跑一次。</b>跑完 SK_c 就固定下来了 —— 因为 PK_c 要预装到门锁上，
 * 换了私钥，所有锁预装的公钥立刻失效。所以这个工具默认<b>不覆盖已存在的文件</b>。
 *
 * <p>用法（工程根目录）：
 * <pre>
 * java -cp "target/classes" com.smarthome.permission.demo.KeyGen
 * </pre>
 * 默认写到 {@code secrets/sk_c.pem} 和 {@code secrets/pk_c.pem}；
 * 也可传路径：{@code ... KeyGen D:/某处/sk_c.pem}
 */
public class KeyGen {

    public static void main(String[] args) throws Exception {
        Path skPath = Path.of(args.length > 0 ? args[0] : "secrets/sk_c.pem");
        Path pkPath = skPath.resolveSibling("pk_c.pem");

        if (Files.exists(skPath)) {
            System.out.println("⚠️  已存在，没有覆盖：" + skPath.toAbsolutePath());
            System.out.println();
            System.out.println("    SK_c 换掉之后，所有门锁上预装的 PK_c 全部失效，必须挨台重新预装。");
            System.out.println("    确实要换的话：先把旧的两个文件改名备份，再重跑这个工具。");
            return;
        }

        KeyPair keyPair = CryptoCommon.generateKeyPair();

        Files.createDirectories(skPath.getParent());
        Files.writeString(skPath, CryptoCommon.privateKeyToPkcs8Pem(keyPair.getPrivate()), StandardCharsets.UTF_8);
        Files.writeString(pkPath, CryptoCommon.publicKeyToPem(keyPair.getPublic()), StandardCharsets.UTF_8);

        System.out.println("已生成云侧签名密钥对：");
        System.out.println("  SK_c（私钥，自己留好、别外传）: " + skPath.toAbsolutePath());
        System.out.println("  PK_c（公钥，交给锁端预装）    : " + pkPath.toAbsolutePath());
        System.out.println();
        System.out.println("PK_c 的 SPKI Base64（发给李浩，锁端预装用）:");
        System.out.println(CryptoCommon.publicKeyToSpkiBase64(keyPair.getPublic()));
    }
}

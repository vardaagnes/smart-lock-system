package com.smarthome.permission.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 公共加密库 · Java 侧（M1 最小集）。
 *
 * <p>与 FIDO2 组 Python 侧 {@code crypto_common.py} 一一对应 —— 两端必须产出<b>逐字节一致</b>的结果，
 * 否则跨语言验签必失败。M1 的验收线就是「Java 签 → Python 验」能过。
 *
 * <p>本类只做往返验签需要的那部分：
 * <ul>
 *   <li>P-256（secp256r1）密钥对生成</li>
 *   <li>ECDSA 签名 / 验签，签名编码 = <b>DER</b>（冻结单 5.2，09-14 定稿）</li>
 *   <li>公钥的两种导出 / 导入格式（SPKI Base64 / 65 字节非压缩点 Base64）</li>
 * </ul>
 *
 * <p><b>为什么签名固定用 {@code SHA256withECDSA}</b>：Java 这个算法名产出的就是 DER 编码的签名，
 * 与 Python 的 {@code ec.ECDSA(hashes.SHA256())} 默认输出一致，两端零转换。
 * 不要改成 {@code "ECDSA"}（那个不指定摘要算法，行为随 Provider 变）。
 */
public final class CryptoCommon {

    private CryptoCommon() {
    }

    /** 曲线名：secp256r1 = P-256 = prime256v1，同一个东西的三个名字 */
    private static final String CURVE = "secp256r1";

    /** 签名算法名，见类注释 */
    private static final String SIGN_ALGORITHM = "SHA256withECDSA";

    /** P-256 一个坐标的字节数（256 bit = 32 字节） */
    private static final int COORD_SIZE = 32;

    // ==================== 密钥 ====================

    /**
     * 生成一对 P-256 密钥。
     * 云侧用这个生成 SK_c（私钥，自己留着签票）和 PK_c（公钥，交给锁端预装用来验签）。
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(CURVE));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成 P-256 密钥对失败", e);
        }
    }

    // ==================== 私钥 / 公钥的 PEM 落盘格式 ====================
    //
    // SK_c 存成 PKCS#8 PEM（Java 的 getEncoded() 本来就是 PKCS#8 DER，套个 PEM 头尾即可）。
    // 用 PEM 不用裸 DER，是为了「能直接打开看、能贴给别人、能和其他语言互认」。

    /** 私钥导出为 PKCS#8 PEM（云侧 SK_c 的落盘格式） */
    public static String privateKeyToPkcs8Pem(PrivateKey privateKey) {
        return toPem("PRIVATE KEY", privateKey.getEncoded());
    }

    /** 从 PKCS#8 PEM 读回私钥 */
    public static PrivateKey privateKeyFromPkcs8Pem(String pemText) {
        try {
            byte[] der = Base64.getDecoder().decode(stripPemArmor(pemText));
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("私钥解析失败（应为 PKCS#8 PEM）", e);
        }
    }

    /** 公钥导出为 PEM（内容就是 SPKI 的 Base64，只是换了排版） */
    public static String publicKeyToPem(PublicKey publicKey) {
        return toPem("PUBLIC KEY", publicKey.getEncoded());
    }

    private static String toPem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private static String stripPemArmor(String pemText) {
        return pemText.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }

    // ==================== 签名 / 验签 ====================

    /**
     * 对 canonical 串签名。
     *
     * @param canonical 待签名内容，由 {@code TicketService.canonicalString(ticket)} 产出
     * @param privateKey 云侧私钥 SK_c
     * @return Base64(DER 签名)，直接塞进票的 {@code sig_c} 字段
     */
    public static String sign(String canonical, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(SIGN_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    /**
     * 验签。锁端（Python）用完全相同的规则：canonical 串 + Base64(DER) 签名 + PK_c。
     *
     * <p>注意这里把异常一律吞成 {@code false} —— 签名格式不对、Base64 解不开，对调用方来说
     * 都是「验不过」，不该往上抛异常（否则一个坏签名能把验签服务打挂）。
     */
    public static boolean verify(String canonical, String signatureBase64, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance(SIGN_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 公钥编码 ====================
    //
    // 冻结单 5.2（09-23 已定）：**公钥编码 = X.509 SubjectPublicKeyInfo（Base64）**。
    // 两端都是各自标准库的默认行为，零转换；并且与现有「设备注册传公钥」的格式一致。
    // 正式代码走 publicKeyToSpkiBase64 / publicKeyFromSpkiBase64 这一对。
    //
    // publicKeyToRawBase64（65 字节非压缩点）是当初两种格式未定时留下的**对照实现**：
    // 用它做过双向验证，证明两种编码指向同一把钥匙（见 docs/素材/m1 的验签记录）。
    // 正式代码不要用 —— 留着只是为了让那段验证可复现，也防止日后有人以为它才是约定。

    /**
     * 【正式用这个】X.509 SubjectPublicKeyInfo，Base64 后约 124 字符。
     * Java 原生 {@code getEncoded()} 直接给；Python 侧 {@code load_pem_public_key} /
     * {@code public_bytes(SubjectPublicKeyInfo)} 默认就是它。
     */
    public static String publicKeyToSpkiBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /** 解析格式一的公钥 */
    public static PublicKey publicKeyFromSpkiBase64(String base64) {
        try {
            return KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("公钥解析失败（SPKI Base64）", e);
        }
    }

    /**
     * 【对照用，正式代码不用】65 字节非压缩点 {@code 0x04‖X‖Y}，Base64 后 88 字符。
     * Python 侧 {@code public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)} 直接产出。
     * 保留原因见上面的区块注释。
     */
    public static String publicKeyToRawBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKeyToRaw(publicKey));
    }

    /** 解析格式二的公钥 */
    public static PublicKey publicKeyFromRawBase64(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64);
        if (raw.length != 1 + COORD_SIZE * 2 || raw[0] != 0x04) {
            throw new IllegalArgumentException("公钥格式不对：应为 65 字节非压缩点（0x04 开头），实际 " + raw.length + " 字节");
        }
        try {
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 1 + COORD_SIZE));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 1 + COORD_SIZE, raw.length));
            return KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256Params()));
        } catch (Exception e) {
            throw new IllegalArgumentException("公钥解析失败（65 字节格式）", e);
        }
    }

    private static byte[] publicKeyToRaw(PublicKey publicKey) {
        ECPoint w = ((ECPublicKey) publicKey).getW();
        byte[] out = new byte[1 + COORD_SIZE * 2];
        out[0] = 0x04;
        copyFixedWidth(w.getAffineX(), out, 1);
        copyFixedWidth(w.getAffineY(), out, 1 + COORD_SIZE);
        return out;
    }

    /**
     * BigInteger → 定长 32 字节。
     * 坑：坐标的数值小的时候 {@code toByteArray()} 会返回不足 32 字节（前面没有零），
     * 直接拷过去会让后面的 Y 坐标错位，所以必须左补零对齐。
     */
    private static void copyFixedWidth(BigInteger value, byte[] dest, int offset) {
        byte[] raw = value.toByteArray();
        int srcPos = Math.max(0, raw.length - COORD_SIZE);
        int len = Math.min(raw.length, COORD_SIZE);
        System.arraycopy(raw, srcPos, dest, offset + COORD_SIZE - len, len);
    }

    /** P-256 的曲线参数（从 JCA 取，不手写常量） */
    private static ECParameterSpec p256Params() throws Exception {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(CURVE));
        return params.getParameterSpec(ECParameterSpec.class);
    }
}

package com.smarthome.permission.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;

/**
 * 云侧签名私钥 SK_c 的持有者。
 *
 * <p><b>为什么私钥必须长期不变</b>：对应的公钥 PK_c 是<b>预装在门锁上</b>的（方案 6.4 离线验签）。
 * SK_c 一换，所有锁预装的 PK_c 立刻全部失效，得挨台重新预装 —— 所以绝不能在启动时现生成。
 *
 * <p><b>为什么存文件，不存数据库</b>：
 * <ul>
 *   <li>备份数据库 = 连签名私钥一起泄露，而数据库备份通常管得比私钥文件松得多；</li>
 *   <li>密钥轮换、迁移、做离线签名都要动 SQL，比换个文件麻烦；</li>
 *   <li>这是通行做法 —— Spring Boot 自己的 {@code server.ssl.key-store} 也是文件。</li>
 * </ul>
 *
 * <p><b>没配密钥时不报错、不起不来</b>：{@link #isReady()} 返回 false，票据的 {@code sig_c}
 * 回落到占位符。这样别人拿到工程不配密钥也能把服务跑起来，只是签不出能在锁端验过的票。
 */
@Component
public class CloudKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(CloudKeyHolder.class);

    private final String skcPath;

    /** 没配好时为 null —— 别直接用 getPrivateKey()，先问 isReady() */
    private PrivateKey privateKey;

    public CloudKeyHolder(@Value("${ticket.sk-c-path:}") String skcPath) {
        this.skcPath = skcPath;
    }

    /**
     * 加载 SK_c。Spring 启动时由 {@code @PostConstruct} 自动调用；
     * 之所以是 public，是让脱离 Spring 的 demo（见 {@code demo.TicketV2Demo}）能复用同一套加载逻辑，
     * 而不是各自再抄一遍 PEM 解析 —— 抄一份就多一处会跟真实现走偏的地方。
     */
    @PostConstruct
    public void load() {
        if (skcPath == null || skcPath.isBlank()) {
            log.warn("未配置 ticket.sk-c-path —— 票据将以占位签名签发，锁端验不过");
            return;
        }
        Path path = Path.of(skcPath);
        if (!Files.exists(path)) {
            log.warn("SK_c 私钥文件不存在：{} —— 票据将以占位签名签发。"
                    + "跑一次 demo.KeyGen 生成它", path.toAbsolutePath());
            return;
        }
        try {
            String pem = Files.readString(path, StandardCharsets.UTF_8);
            this.privateKey = CryptoCommon.privateKeyFromPkcs8Pem(pem);
            log.info("已加载云侧签名私钥 SK_c：{}", path.toAbsolutePath());
        } catch (Exception e) {
            // 配置了路径、文件也在，但读不出来 —— 这是配置错误，必须让启动失败，
            // 不能悄悄回落成占位签名（那会签出一堆锁端验不过的废票，且很难发现）。
            throw new IllegalStateException("SK_c 私钥加载失败：" + path.toAbsolutePath(), e);
        }
    }

    /** 私钥是否就绪。没配、文件不存在时都是 false */
    public boolean isReady() {
        return privateKey != null;
    }

    public PrivateKey getPrivateKey() {
        if (privateKey == null) {
            throw new IllegalStateException("SK_c 未加载，调用前先判断 isReady()");
        }
        return privateKey;
    }
}

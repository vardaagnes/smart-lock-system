package com.smarthome.permission.service;

import com.smarthome.permission.dto.CrlResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRL 签名串的规则测试（纯函数，不起 Spring、不连库）。
 *
 * <p><b>为什么这些规则要单独钉死</b>：签名串是跨组契约，改一个字锁端就全验不过，
 * 而报错只会说「无效签名」，看不出是哪里不一致。现在这些规则只写在注释和文档里，
 * 没有测试拦着 —— 谁哪天顺手调一下格式，CI 是绿的，问题要到联调才炸。
 *
 * <p>对照实现：{@code docs/素材/m2/verify_crl.py} 的 {@code canonical()}，
 * 两边必须逐字节一致。
 */
class CrlServiceCanonicalTest {

    private static CrlResponse.Entry entry(String tId, long rv) {
        return new CrlResponse.Entry(tId, rv);
    }

    @Test
    @DisplayName("常规增量：ver=1&lock_id=..&rv=..&entries=t_id:rv,t_id:rv")
    void canonicalForTwoEntries() {
        String s = CrlService.canonicalString("lock-001", 15L,
                List.of(entry("t-3f8a91c2d7e4", 12L), entry("t-b05e6a4419cc", 15L)));

        assertThat(s).isEqualTo(
                "ver=1&lock_id=lock-001&rv=15&entries=t-3f8a91c2d7e4:12,t-b05e6a4419cc:15");
    }

    @Test
    @DisplayName("空名单必须保留 &entries= 尾巴 —— 省掉它就能伪造「名单为空」")
    void canonicalForEmptyListKeepsTrailingEntries() {
        String s = CrlService.canonicalString("lock-001", 15L, new ArrayList<>());

        assertThat(s).isEqualTo("ver=1&lock_id=lock-001&rv=15&entries=");
        // 等价写法，但意图更直白：尾巴不能少
        assertThat(s).endsWith("&entries=");
    }

    @Test
    @DisplayName("条目顺序照传入顺序拼，不重排 —— 顺序也是签名的一部分")
    void canonicalPreservesOrder() {
        List<CrlResponse.Entry> forward = List.of(entry("t-a", 18L), entry("t-b", 19L));
        List<CrlResponse.Entry> reversed = List.of(entry("t-b", 19L), entry("t-a", 18L));

        assertThat(CrlService.canonicalString("lock-001", 20L, forward))
                .contains("entries=t-a:18,t-b:19");

        // 反过来必须是另一个串，否则「重排条目」这种篡改验不出来
        assertThat(CrlService.canonicalString("lock-001", 20L, reversed))
                .isNotEqualTo(CrlService.canonicalString("lock-001", 20L, forward));
    }

    @Test
    @DisplayName("lock_id 进签名：同样的条目换一把锁，串就该不同")
    void lockIdIsPartOfSignature() {
        List<CrlResponse.Entry> entries = List.of(entry("t-a", 12L));

        assertThat(CrlService.canonicalString("lock-001", 15L, entries))
                .isNotEqualTo(CrlService.canonicalString("lock-002", 15L, entries));
    }

    @Test
    @DisplayName("全量拉取（lock_id 为 null）拼成 lock_id= 空值，不是字符串 null")
    void nullLockIdRendersAsEmptyValue() {
        String s = CrlService.canonicalString(null, 15L, List.of(entry("t-a", 12L)));

        assertThat(s).isEqualTo("ver=1&lock_id=&rv=15&entries=t-a:12");
    }
}

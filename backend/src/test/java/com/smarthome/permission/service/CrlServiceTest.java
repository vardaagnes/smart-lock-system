package com.smarthome.permission.service;

import com.smarthome.permission.crypto.CloudKeyHolder;
import com.smarthome.permission.dto.CrlResponse;
import com.smarthome.permission.entity.TicketBlacklistEntity;
import com.smarthome.permission.repository.TicketBlacklistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRL 拉取的库层测试。
 *
 * <p><b>这里不验签名</b>：{@link CloudKeyHolder} 用空路径构造、不加载私钥，
 * 所以 {@code sig_c} 恒为占位符。签名的跨端验证在 {@code docs/素材/m2/verify_crl.py}，
 * 两件事分开测 —— 混在一起时，验签失败分不清是「查询给错了」还是「串拼错了」。
 *
 * <p>断言全部用相对值（{@code findMaxRv()} 现场取），不用写死的 rv ——
 * 开发库里可能已有历史撤销记录，写死会在有存量数据时假失败。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CrlServiceTest {

    @Autowired
    private TicketBlacklistRepository blacklistRepo;

    private CrlService service;

    @BeforeEach
    void setUp() {
        // 空路径 = 不加载 SK_c，签名回落成占位符
        service = new CrlService(blacklistRepo, new CloudKeyHolder(""));
    }

    /** 直接往黑名单表插一条，rv 取「当前最大 + 1」，与真实撤销路径的编号方式一致 */
    private void saveRevoked(String tId, String lockId) {
        TicketBlacklistEntity e = new TicketBlacklistEntity();
        e.setTId(tId);
        e.setLockId(lockId);
        e.setRv(blacklistRepo.findMaxRv() + 1);
        e.setRevokedAt(Instant.now());
        blacklistRepo.save(e);
    }

    private long rvOf(String tId) {
        return blacklistRepo.findById(tId).orElseThrow().getRv();
    }

    @Test
    @DisplayName("按锁拉增量：只返回本锁、rv 大于游标的条目，且按 rv 升序")
    void pullFiltersByLockAndCursor() {
        saveRevoked("t-crl-p1", "d-crl-pull");
        saveRevoked("t-crl-p2", "d-crl-pull");
        saveRevoked("t-crl-p3", "d-crl-other");   // 别的锁，不该出现

        CrlResponse all = service.pull("d-crl-pull", 0L);
        assertThat(all.getEntries()).extracting(CrlResponse.Entry::getTId)
                .containsExactly("t-crl-p1", "t-crl-p2");
        assertThat(all.getLockId()).isEqualTo("d-crl-pull");

        // 游标推到第一张之后，只剩第二张
        CrlResponse afterFirst = service.pull("d-crl-pull", rvOf("t-crl-p1"));
        assertThat(afterFirst.getEntries()).extracting(CrlResponse.Entry::getTId)
                .containsExactly("t-crl-p2");

        // 游标追平最新，就没有增量了 —— 幂等重试拿到的应该是空名单
        CrlResponse caughtUp = service.pull("d-crl-pull", rvOf("t-crl-p2"));
        assertThat(caughtUp.getEntries()).isEmpty();
    }

    @Test
    @DisplayName("rv 一律是全局最大，不是过滤后条目的最大 —— 否则锁会重复拉同一段")
    void rvIsGlobalMaxNotFilteredMax() {
        saveRevoked("t-crl-g1", "d-crl-g");
        // 后撤的这一条属于别的锁，全局 rv 被它推高
        saveRevoked("t-crl-g2", "d-crl-elsewhere");
        long globalMax = blacklistRepo.findMaxRv();

        CrlResponse r = service.pull("d-crl-g", 0L);

        assertThat(r.getEntries()).extracting(CrlResponse.Entry::getTId)
                .containsExactly("t-crl-g1");
        assertThat(r.getRv()).isEqualTo(globalMax);
    }

    @Test
    @DisplayName("不传 lock_id = 全量拉取，返回所有锁的条目，lock_id 为空串")
    void pullWithoutLockIdReturnsAll() {
        saveRevoked("t-crl-all1", "d-crl-all-a");
        saveRevoked("t-crl-all2", "d-crl-all-b");

        CrlResponse r = service.pull(null, 0L);

        assertThat(r.getEntries()).extracting(CrlResponse.Entry::getTId)
                .contains("t-crl-all1", "t-crl-all2");
        assertThat(r.getLockId()).isEmpty();
    }

    @Test
    @DisplayName("没配 SK_c 时返回占位签名，不抛异常 —— 失败方向交给锁端拒绝")
    void placeholderSignatureWhenNoKey() {
        saveRevoked("t-crl-ph", "d-crl-ph");

        CrlResponse r = service.pull("d-crl-ph", 0L);

        assertThat(r.getSigC()).isEqualTo("PLACEHOLDER_SIG");
        // 名单本身照常给出：锁端拿到占位签名会整份丢弃，这是预期行为
        assertThat(r.getEntries()).hasSize(1);
    }
}

package com.smarthome.permission.service;

import com.smarthome.permission.dto.RevokeBatchResult;
import com.smarthome.permission.dto.RevokeResult;
import com.smarthome.permission.dto.TicketListItem;
import com.smarthome.permission.entity.TempTicketEntity;
import com.smarthome.permission.entity.TicketBlacklistEntity;
import com.smarthome.permission.repository.TempTicketRepository;
import com.smarthome.permission.repository.TicketBlacklistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 撤销 / 列表的库层测试。
 *
 * <p><b>为什么是 @DataJpaTest 而不是起服务打 HTTP</b>：Controller 要求 JWT，
 * 拉 JWKS 依赖 Keycloak；这里只验本轮新增的库逻辑（rv 分配、幂等、增量查询、列表映射），
 * 不需要鉴权那一层。<b>它验的是真 PostgreSQL</b>（replace = NONE），
 * 所以 from / to 那对必须加引号的列名是真被走了一遍，不是靠 H2 糊过去的。
 *
 * <p>每个用例都在事务里跑、结束回滚，不往开发库里留数据。
 *
 * <p><b>断言全部用相对值，不用绝对值</b>：全局 rv 是共享计数器、库里可能已有历史记录，
 * 写死 {@code rv == 1} 这种断言在有存量数据时会假失败。
 * 同理每个用例用自己专属的 lock_id，避免和历史数据串味。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketRevokeServiceTest {

    @Autowired
    private TempTicketRepository ticketRepo;

    @Autowired
    private TicketBlacklistRepository blacklistRepo;

    private TicketRevokeService service;

    @BeforeEach
    void setUp() {
        service = new TicketRevokeService(ticketRepo, blacklistRepo);
    }

    // ---------------------------------------------------------------- 辅助

    private TempTicketEntity saveTicket(String tId, String lockId) {
        long now = Instant.now().getEpochSecond();
        TempTicketEntity t = new TempTicketEntity();
        t.setTId(tId);
        t.setLockId(lockId);
        t.setPerm("open");
        t.setFrom(now);
        t.setTo(now + 3600);
        t.setMaxUses(3);
        t.setSalt("AAAAAAAAAAAAAAAAAAAAAA==");
        t.setPinHash("0".repeat(64));
        t.setIat(now);
        t.setSigC("PLACEHOLDER_SIG");
        t.setStatus("ACTIVE");
        t.setCreatedAt(Instant.now());
        return ticketRepo.save(t);
    }

    /**
     * 长期用户票。与 {@link #saveTicket} 的差别只在两处：类型 + user_id，
     * 外加 max_uses=0（不限次哨兵）——这三处正是 v2 契约里长期票与访客票的全部差异。
     */
    private TempTicketEntity saveLongTermTicket(String tId, String lockId, String userId) {
        TempTicketEntity t = saveTicket(tId, lockId);
        t.setTicketType("longterm");
        t.setUserId(userId);
        t.setMaxUses(0);
        return ticketRepo.save(t);
    }

    private long rvOf(List<TicketListItem> items, String tId) {
        return items.stream().filter(i -> i.getTId().equals(tId)).findFirst().orElseThrow().getRv();
    }

    // ---------------------------------------------------------------- 撤销

    @Test
    @DisplayName("撤销一张票：写黑名单 + 把票状态改成 REVOKED")
    void revokeWritesBlacklistAndFlipsStatus() {
        saveTicket("t-rev-1", "d-rev");

        RevokeResult r = service.revoke("t-rev-1", "访客提前离开", "admin-1").getResults().get(0);

        assertThat(r.getTId()).isEqualTo("t-rev-1");
        // lock_id 取自票本身 —— 请求体里根本没有这个字段，调用方没机会撤到别的锁底下
        assertThat(r.getLockId()).isEqualTo("d-rev");
        assertThat(r.getRv()).isPositive();
        assertThat(r.getRevokedAt()).isNotNull();

        assertThat(blacklistRepo.existsByTId("t-rev-1")).isTrue();
        assertThat(ticketRepo.findById("t-rev-1").orElseThrow().getStatus()).isEqualTo("REVOKED");

        TicketBlacklistEntity entry = blacklistRepo.findById("t-rev-1").orElseThrow();
        assertThat(entry.getReason()).isEqualTo("访客提前离开");
        assertThat(entry.getRevokedBy()).isEqualTo("admin-1");
    }

    @Test
    @DisplayName("rv 全局单调递增：连撤两张，后一张恰好 +1")
    void rvIsGloballyMonotonic() {
        saveTicket("t-rv-a", "d-rv");
        saveTicket("t-rv-b", "d-rv");

        long first = service.revoke("t-rv-a", null, "admin").getResults().get(0).getRv();
        long second = service.revoke("t-rv-b", null, "admin").getResults().get(0).getRv();

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    @DisplayName("重复撤销幂等：返回原 rv，不推进全局计数器，不新增记录")
    void revokeIsIdempotent() {
        saveTicket("t-idem", "d-idem");

        RevokeResult first = service.revoke("t-idem", "第一次", "admin").getResults().get(0);
        long rvAfterFirst = blacklistRepo.findMaxRv();
        int rowsAfterFirst = blacklistRepo.findByLockIdAndRvGreaterThanOrderByRvAsc("d-idem", 0).size();

        RevokeResult second = service.revoke("t-idem", "重复点了一次", "admin").getResults().get(0);

        assertThat(second.getRv()).isEqualTo(first.getRv());
        // 关键：计数器没被无意义推高 —— 否则锁端要拉一堆空增量
        assertThat(blacklistRepo.findMaxRv()).isEqualTo(rvAfterFirst);
        assertThat(blacklistRepo.findByLockIdAndRvGreaterThanOrderByRvAsc("d-idem", 0))
                .hasSize(rowsAfterFirst);
    }

    @Test
    @DisplayName("撤销不存在的票：抛 IllegalArgumentException（由 controller 翻成 400）")
    void revokeUnknownTicketThrows() {
        assertThatThrownBy(() -> service.revoke("t-not-exist", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("t-not-exist");
    }

    // ------------------------------------------------------ 按人批量撤销（v2）

    @Test
    @DisplayName("按 user_id 批量撤：撤到该用户全部长期票（跨锁），不碰访客票与其他人")
    void revokeByUserRevokesAllLongTermTicketsAcrossLocks() {
        saveLongTermTicket("t-u1-a", "d-u1-a", "u-batch");
        saveLongTermTicket("t-u1-b", "d-u1-b", "u-batch");   // 同一人在另一把锁上
        saveLongTermTicket("t-u2-1", "d-u1-a", "u-other");   // 别人
        saveTicket("t-visitor-1", "d-u1-a");                 // 访客票，user_id 是空串

        long rvBefore = blacklistRepo.findMaxRv();
        RevokeBatchResult r = service.revokeByUser("u-batch", "口令泄露", "admin");

        assertThat(r.getUserId()).isEqualTo("u-batch");
        assertThat(r.getResults()).extracting(RevokeResult::getTId)
                .containsExactlyInAnyOrder("t-u1-a", "t-u1-b");
        assertThat(r.getRevokedCount()).isEqualTo(2);

        assertThat(ticketRepo.findById("t-u1-a").orElseThrow().getStatus()).isEqualTo("REVOKED");
        assertThat(ticketRepo.findById("t-u1-b").orElseThrow().getStatus()).isEqualTo("REVOKED");

        // 别人的票、访客票一律不动 —— 尤其是访客票，它的 user_id 也是空串，最容易误伤
        assertThat(ticketRepo.findById("t-u2-1").orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(ticketRepo.findById("t-visitor-1").orElseThrow().getStatus()).isEqualTo("ACTIVE");

        // rv 是事件序号，不是批次号：撤两张就推进两格
        assertThat(blacklistRepo.findMaxRv()).isEqualTo(rvBefore + 2);
    }

    @Test
    @DisplayName("按人批量撤幂等：重复调用不推进 rv，revoked_count 归零但 results 仍回全部命中")
    void revokeByUserIsIdempotent() {
        saveLongTermTicket("t-ui-1", "d-ui", "u-idem-batch");
        saveLongTermTicket("t-ui-2", "d-ui", "u-idem-batch");

        RevokeBatchResult first = service.revokeByUser("u-idem-batch", null, "admin");
        assertThat(first.getRevokedCount()).isEqualTo(2);
        long rvAfterFirst = blacklistRepo.findMaxRv();

        RevokeBatchResult second = service.revokeByUser("u-idem-batch", null, "admin");
        assertThat(second.getRevokedCount()).isZero();          // 这次没做新事
        assertThat(second.getResults()).hasSize(2);             // 但仍告诉你这两张是什么状态
        assertThat(blacklistRepo.findMaxRv()).isEqualTo(rvAfterFirst);
    }

    @Test
    @DisplayName("按人批量撤：user_id 为空必须拒绝 —— 空串会命中全库访客票")
    void revokeByUserRejectsBlankUserId() {
        saveTicket("t-guard-1", "d-guard");

        assertThatThrownBy(() -> service.revokeByUser("", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.revokeByUser("   ", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.revokeByUser(null, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);

        // 关键：这几次调用不能顺手把访客票撤了
        assertThat(ticketRepo.findById("t-guard-1").orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("按人批量撤：查无此人时返回空结果，不当成错误")
    void revokeByUserWithNoTicketsReturnsEmpty() {
        RevokeBatchResult r = service.revokeByUser("u-nobody-at-all", null, "admin");

        assertThat(r.getResults()).isEmpty();
        assertThat(r.getRevokedCount()).isZero();
        assertThat(r.getUserId()).isEqualTo("u-nobody-at-all");
    }

    // ------------------------------------------------------- 增量同步（锁端用）

    @Test
    @DisplayName("增量查询：只返回本锁、且 rv 大于游标的记录")
    void incrementalQueryFiltersByLockAndCursor() {
        saveTicket("t-inc-1", "d-inc");
        saveTicket("t-inc-2", "d-inc");
        saveTicket("t-inc-3", "d-other");

        long rv1 = service.revoke("t-inc-1", null, "admin").getResults().get(0).getRv();
        long rv2 = service.revoke("t-inc-2", null, "admin").getResults().get(0).getRv();
        service.revoke("t-inc-3", null, "admin");   // 别的锁，不该出现在 d-inc 的增量里

        // 锁端本地 last_rv = rv1，上线后只该拉到 t-inc-2
        assertThat(blacklistRepo.findByLockIdAndRvGreaterThanOrderByRvAsc("d-inc", rv1))
                .extracting(TicketBlacklistEntity::getTId)
                .containsExactly("t-inc-2");

        // 游标推到 rv2 之后，没有新东西了
        assertThat(blacklistRepo.findByLockIdAndRvGreaterThanOrderByRvAsc("d-inc", rv2)).isEmpty();

        // 游标 0 = 从没同步过（哨兵值），应拿到本锁全部记录
        assertThat(blacklistRepo.findByLockIdAndRvGreaterThanOrderByRvAsc("d-inc", 0))
                .extracting(TicketBlacklistEntity::getTId)
                .containsExactly("t-inc-1", "t-inc-2");
    }

    // ---------------------------------------------------------------- 列表

    @Test
    @DisplayName("列表：按锁过滤、按状态过滤（大小写不敏感）、已撤销票带回 rv，未撤销为 0")
    void listFiltersAndMapsRv() {
        saveTicket("t-lst-1", "d-lst");
        saveTicket("t-lst-2", "d-lst");
        saveTicket("t-lst-3", "d-lst-other");

        long rv = service.revoke("t-lst-1", null, "admin").getResults().get(0).getRv();

        List<TicketListItem> all = service.list("d-lst", null);
        assertThat(all).extracting(TicketListItem::getTId)
                .containsExactlyInAnyOrder("t-lst-1", "t-lst-2");
        assertThat(rvOf(all, "t-lst-1")).isEqualTo(rv);
        assertThat(rvOf(all, "t-lst-2")).isZero();     // 哨兵值：未撤销

        assertThat(service.list("d-lst", "REVOKED"))
                .extracting(TicketListItem::getTId).containsExactly("t-lst-1");
        // 状态过滤大小写不敏感
        assertThat(service.list("d-lst", "active"))
                .extracting(TicketListItem::getTId).containsExactly("t-lst-2");

        // 别的锁的票不该串进来
        assertThat(service.list("d-nobody", null)).isEmpty();
        assertThat(service.list("d-lst-other", null))
                .extracting(TicketListItem::getTId).containsExactly("t-lst-3");

        // 字段映射：列表项要够前端显示有效期 / 次数 / 状态
        TicketListItem item = all.stream().filter(i -> i.getTId().equals("t-lst-1")).findFirst().orElseThrow();
        assertThat(item.getPerm()).isEqualTo("open");
        assertThat(item.getMaxUses()).isEqualTo(3);
        assertThat(item.getStatus()).isEqualTo("REVOKED");
        assertThat(item.getTo()).isGreaterThan(item.getFrom());
    }

    @Test
    @DisplayName("列表不传 lock_id 时返回全部（含其它锁）")
    void listWithoutLockIdReturnsAll() {
        saveTicket("t-all-1", "d-all-a");
        saveTicket("t-all-2", "d-all-b");

        assertThat(service.list(null, null))
                .extracting(TicketListItem::getTId)
                .contains("t-all-1", "t-all-2");
    }
}

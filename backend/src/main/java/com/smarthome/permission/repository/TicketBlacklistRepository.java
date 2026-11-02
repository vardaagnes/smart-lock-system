package com.smarthome.permission.repository;

import com.smarthome.permission.entity.TicketBlacklistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TicketBlacklistRepository extends JpaRepository<TicketBlacklistEntity, String> {

    /**
     * 这张票是否已被撤销。
     *
     * <p><b>为什么不用 {@code existsByTId} 这种推导查询</b>：字段叫 {@code tId}，
     * Lombok 生成的 getter 是 {@code getTId()}，JavaBean 属性名被推成 {@code TId}；
     * 而 Hibernate 元模型里的属性名是字段名 {@code tId}。两端对不上，
     * 推导解析直接报 {@code Could not resolve attribute 'TId'} —— 这不是运行时才炸，
     * 是<b>启动建 bean 时就炸</b>，整个服务起不来。故这里显式写 JPQL。
     *
     * <p>同理，以后给这个实体加任何 {@code ...ByTId} 的推导方法都会踩同一个坑，
     * 一律改成显式 {@code @Query}。（{@code findByLockId...} 没事：
     * {@code LockId} 会被正常首字母小写化成 {@code lockId}，跟字段名一致。）
     */
    @Query("select count(b) > 0 from TicketBlacklistEntity b where b.tId = :tId")
    boolean existsByTId(@Param("tId") String tId);

    /**
     * 锁端增量同步：取这把锁在 rv 之后的撤销记录。
     * 锁本地存 last_rv，上线时拿它当游标 —— 只拉自己这把锁的，不拉全量。
     */
    List<TicketBlacklistEntity> findByLockIdAndRvGreaterThanOrderByRvAsc(String lockId, long rv);

    /** 全量对账 / 网关取增量（不分锁）用 */
    List<TicketBlacklistEntity> findByRvGreaterThanOrderByRvAsc(long rv);

    /**
     * 下一个全局 rv = 当前最大值 + 1。
     *
     * <p>空表时 COALESCE 兜 0，所以第一张撤销票拿到 rv=1 ——
     * <b>rv 从 1 开始，0 是「什么都没同步过」的哨兵值</b>，锁端初始化 last_rv 就用 0。
     *
     * <p>已知限制：READ_COMMITTED 隔离级别下，两个并发撤销可能读到同一个 MAX。
     * 这里不加重试，靠 rv 列的唯一约束把它变成异常（见 TicketBlacklistEntity 的注释）——
     * 报错比静默写坏游标好。本项目管理员操作为主，冲突概率极低。
     */
    @Query("select coalesce(max(b.rv), 0) from TicketBlacklistEntity b")
    long findMaxRv();
}

package com.smarthome.permission.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * CRL 拉取响应（{@code GET /api/ticket/crl} 的响应体）。冻结单 §5.1 第 1 条 / §5.2「CRL 拉取响应」。
 *
 * <p>字段名一律 snake_case，靠每个字段上的 {@code @JsonProperty} 落实 —— 与项目既有约定一致，
 * <b>不加全局 Jackson 命名策略</b>（那会把已经上线的 {@code /api/permission/*} 响应体一起改坏）。
 *
 * <p><b>为什么响应体里带 lock_id</b>：它是签名串的一部分。少了它，一份「lock-A 的撤销名单为空」的
 * 合法签名可以被原样重放给 lock-B —— 而 lock-B 无从分辨，这正好绕过了「防伪造空名单」这条设计目的。
 * 带上并进签名之后，B 用「自己的 lock_id + 响应里的条目」复算，签名必然对不上。
 *
 * <p><b>rv 是全局版本号，不是 entries 里最大的那个</b>：游标表示「整个撤销事件流读到哪了」。
 * 这把锁本次没有新条目时，rv 照样往前走；返回过滤后的最大 rv 的话，锁下次会重复拉同一段，
 * 而且中间可能夹着别的锁的编号，游标就乱了。
 */
@Data
public class CrlResponse {

    /** 本次拉取对应的锁；全量拉取（网关对账）时为空串。进签名 */
    @JsonProperty("lock_id")
    private String lockId;

    /** 全局撤销版本号，锁把它存成 last_rv，下次当 since_rv 传回来 */
    @JsonProperty("rv")
    private long rv;

    /** 本次增量条目，按 rv 升序。空列表是合法且必须能签的状态 */
    @JsonProperty("entries")
    private List<Entry> entries = new ArrayList<>();

    /** 云用 SK_c 对 canonical 串的签名，Base64(DER)。锁端用预装的 PK_c 验 */
    @JsonProperty("sig_c")
    private String sigC;

    /** 单条撤销记录。JSON 里就是 {@code {"t_id":"..","rv":12}} */
    @Data
    public static class Entry {

        @JsonProperty("t_id")
        private String tId;

        @JsonProperty("rv")
        private long rv;

        public Entry() {
        }

        public Entry(String tId, long rv) {
            this.tId = tId;
            this.rv = rv;
        }
    }
}

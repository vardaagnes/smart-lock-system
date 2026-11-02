package com.smarthome.permission.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 撤销访客临时票请求体（POST /api/ticket/revoke）。
 *
 * <p>字段名显式钉成 snake_case，与接口冻结单一致（同 {@link IssueTicketRequest} 的做法）。
 * ⚠️ 仍然不要改成全局 Jackson 命名策略 —— 理由见 IssueTicketRequest 的注释。
 */
@Data
public class RevokeTicketRequest {

    /** 要撤销的票号，形如 t-xxxxxxxxxxxx。与 {@link #userId} <b>二选一</b> */
    @JsonProperty("t_id")
    private String tId;

    /**
     * 长期用户标识（v2）——传它表示「把这个人的长期凭证全撤了」。与 {@link #tId} <b>二选一</b>。
     *
     * <p><b>绝不能传空串</b>：访客票的 {@code user_id} 就是空串，按空串查会命中全库所有访客票。
     * Controller 会先挡「空白」，Service 里还有一道，两道都不省。
     *
     * <p>它跟发起操作的管理员不是一回事 —— 请求体里没有、也不该有「操作人」字段，操作人从 JWT 取。
     */
    @JsonProperty("user_id")
    private String userId;

    /** 撤销原因，可空 */
    @JsonProperty("reason")
    private String reason;
}

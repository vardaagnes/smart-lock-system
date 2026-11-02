package com.smarthome.permission.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 撤销结果（{@code POST /api/ticket/revoke} 的响应）。<b>单张撤与按人批量撤共用一个形状。</b>
 *
 * <p><b>为什么不像原来那样直接返回一个 {@link RevokeResult}</b>：冻结单 §5.1 允许请求体传
 * {@code t_id} <b>或</b> {@code user_id}，后者一次可能命中多张票。如果单张返回对象、批量返回数组，
 * 调用方就得写两个分支。统一成「总是带 {@code results} 数组」之后，前端一套逻辑走到底，
 * 单张撤销就是长度为 1 的数组。
 *
 * <p><b>{@code revoked_count} 与 {@code results.size()} 不是一回事</b>：
 * 重复撤销同一张票是幂等的（返回原 rv、不推进全局计数器），这些条目会出现在 {@code results} 里，
 * 但<b>不算</b>本次新撤销。前者回答「你现在手上变成什么样了」，后者回答「这次调用实际做了多少事」。
 */
@Data
public class RevokeBatchResult {

    /** 按人批量撤销时为被撤的用户标识；单张撤销时为 null */
    @JsonProperty("user_id")
    private String userId;

    /** 本次调用<b>新</b>写入黑名单的张数（幂等命中的不算） */
    @JsonProperty("revoked_count")
    private int revokedCount;

    /** 命中的每张票一条，含此前已撤销的（带当初那次的 rv） */
    @JsonProperty("results")
    private List<RevokeResult> results = new ArrayList<>();
}

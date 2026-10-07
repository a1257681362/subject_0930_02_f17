package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 用药并发裁决审计：同一池塘的提交/驳回/撤回并发发起时，库级串行裁决并逐请求留痕。
 * verdict：EFFECTIVE 生效（先拿到池塘锁者）/ CONCURRENT_REJECTED 被拒绝（并发失败者）。
 * 被拒行记录生效请求号与生效操作者、拒绝原因——谁生效、谁被拒可双向追溯。只追加。
 */
@TableName("t_med_concurrent_audit")
public class MedConcurrentAudit extends BaseEntity {

    public static final String VERDICT_EFFECTIVE = "EFFECTIVE";
    public static final String VERDICT_REJECTED = "CONCURRENT_REJECTED";

    private String bizKey;
    private String action;
    private String verdict;
    private String requestId;
    private String operator;
    private String effectiveRequestId;
    private String effectiveOperator;
    private String rejectReason;

    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getEffectiveRequestId() { return effectiveRequestId; }
    public void setEffectiveRequestId(String effectiveRequestId) { this.effectiveRequestId = effectiveRequestId; }
    public String getEffectiveOperator() { return effectiveOperator; }
    public void setEffectiveOperator(String effectiveOperator) { this.effectiveOperator = effectiveOperator; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
}

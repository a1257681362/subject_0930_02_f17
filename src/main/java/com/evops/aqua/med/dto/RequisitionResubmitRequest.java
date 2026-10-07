package com.evops.aqua.med.dto;

import javax.validation.constraints.NotBlank;

/**
 * 撤回重提请求：对已撤回领用单按同一批数据重新提交，生成新版本领用单。
 * 原撤回单只读保留，新单编号带 Vn、批次关联版本递增、提交时间新写入，三者均可辨。
 */
public class RequisitionResubmitRequest {

    @NotBlank(message = "操作者不能为空")
    private String operator;

    @NotBlank(message = "请求号不能为空")
    private String requestId;

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}

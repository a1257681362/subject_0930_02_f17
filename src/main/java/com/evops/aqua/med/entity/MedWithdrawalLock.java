package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDate;

/**
 * 池塘休药期出池锁定：每池一行。
 * lockedUntil 取该池在途（待审核/已审核）领用单各药品休药结束日的最晚值——
 * 不同药品叠加时取最晚结束日；提交/驳回/撤回时重算。
 * source* 记录最晚结束日来自哪张单哪一行（批号/药品可辨），锁定可逐行勾稽，
 * 不会把其他池塘/较早批次的休药期套进来。
 */
@TableName("t_med_withdrawal_lock")
public class MedWithdrawalLock extends BaseEntity {

    private Long pondId;
    private LocalDate lockedUntil;
    private String detail;
    private Long sourceReqId;
    private String sourceReqNo;
    private Long sourceItemId;
    private String sourceBatchNo;
    private String sourceDrugName;

    public Long getPondId() { return pondId; }
    public void setPondId(Long pondId) { this.pondId = pondId; }
    public LocalDate getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(LocalDate lockedUntil) { this.lockedUntil = lockedUntil; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Long getSourceReqId() { return sourceReqId; }
    public void setSourceReqId(Long sourceReqId) { this.sourceReqId = sourceReqId; }
    public String getSourceReqNo() { return sourceReqNo; }
    public void setSourceReqNo(String sourceReqNo) { this.sourceReqNo = sourceReqNo; }
    public Long getSourceItemId() { return sourceItemId; }
    public void setSourceItemId(Long sourceItemId) { this.sourceItemId = sourceItemId; }
    public String getSourceBatchNo() { return sourceBatchNo; }
    public void setSourceBatchNo(String sourceBatchNo) { this.sourceBatchNo = sourceBatchNo; }
    public String getSourceDrugName() { return sourceDrugName; }
    public void setSourceDrugName(String sourceDrugName) { this.sourceDrugName = sourceDrugName; }
}

package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 领用明细：一行一种药品一个批号。
 * 剂量与休药期在建单时冻结快照；休药结束日在提交时计算（本行用药日快照 + 休药期天数）。
 * 行级上下文快照（池塘/用药日/批号编号/药品名/单位/批次关联版本）同样建单即冻结——
 * 同一药品被两个池塘领用不同批号时，每行只认自己的快照，重算休药期不得拿其他池/较早批次的上下文。
 */
@TableName("t_med_requisition_item")
public class MedRequisitionItem extends BaseEntity {

    private Long requisitionId;
    private Long drugId;
    private Long medBatchId;
    private BigDecimal dosagePerM3;
    private BigDecimal estimatedQty;
    private Integer withdrawalDays;
    private LocalDate withdrawalEndDate;
    /** 行级上下文快照：本行所属池塘，与头单一致；冻结后不随后续池塘档案变动。 */
    private Long pondId;
    /** 行级用药日快照：休药结束日 = useDateSnapshot + withdrawalDays。 */
    private LocalDate useDateSnapshot;
    /** 批号编号快照：批次关联即便后续修订，本行仍可辨当时领的是哪一批。 */
    private String batchNo;
    private String drugName;
    private String unit;
    /** 批次关联版本：随领用单 link_version 冻结，撤回重提新版本号可辨。 */
    private Integer batchVersion;

    public Long getRequisitionId() { return requisitionId; }
    public void setRequisitionId(Long requisitionId) { this.requisitionId = requisitionId; }
    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public Long getMedBatchId() { return medBatchId; }
    public void setMedBatchId(Long medBatchId) { this.medBatchId = medBatchId; }
    public BigDecimal getDosagePerM3() { return dosagePerM3; }
    public void setDosagePerM3(BigDecimal dosagePerM3) { this.dosagePerM3 = dosagePerM3; }
    public BigDecimal getEstimatedQty() { return estimatedQty; }
    public void setEstimatedQty(BigDecimal estimatedQty) { this.estimatedQty = estimatedQty; }
    public Integer getWithdrawalDays() { return withdrawalDays; }
    public void setWithdrawalDays(Integer withdrawalDays) { this.withdrawalDays = withdrawalDays; }
    public LocalDate getWithdrawalEndDate() { return withdrawalEndDate; }
    public void setWithdrawalEndDate(LocalDate withdrawalEndDate) { this.withdrawalEndDate = withdrawalEndDate; }
    public Long getPondId() { return pondId; }
    public void setPondId(Long pondId) { this.pondId = pondId; }
    public LocalDate getUseDateSnapshot() { return useDateSnapshot; }
    public void setUseDateSnapshot(LocalDate useDateSnapshot) { this.useDateSnapshot = useDateSnapshot; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getDrugName() { return drugName; }
    public void setDrugName(String drugName) { this.drugName = drugName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Integer getBatchVersion() { return batchVersion; }
    public void setBatchVersion(Integer batchVersion) { this.batchVersion = batchVersion; }
}

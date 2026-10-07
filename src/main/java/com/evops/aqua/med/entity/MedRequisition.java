package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用药领用单：关联池塘、病情（疫病观察单）与药品批号（明细行）。
 * 状态 DRAFT（草稿）/ PENDING（待审核）/ APPROVED（已审核）/ REJECTED（已驳回）/ WITHDRAWN（已撤回）。
 * 提交后计算休药期并锁定预计出池日；撤回只能撤销未审核单（DRAFT/PENDING）。
 * linkVersion 批次关联版本：建单为 1，撤回后重提生成新版本单（编号带 Vn、重提时间新写入），
 * 原撤回单只读保留——历史审核结论不静默重算。
 */
@TableName("t_med_requisition")
public class MedRequisition extends BaseEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    private String reqNo;
    private Long pondId;
    private Long observationId;
    private String diseaseDesc;
    private LocalDate useDate;
    private BigDecimal waterVolumeM3;
    private String status;
    private String applicant;
    private String approvedBy;
    private String approveRemark;
    private LocalDateTime approvedTime;
    private String withdrawBy;
    private LocalDateTime withdrawTime;
    /** 批次关联版本：首提 1，撤回后重提逐版递增；编号与明细快照同步带版本，可辨。 */
    private Integer linkVersion;
    private LocalDateTime submitTime;
    /** 本单是撤回重提单时，指向原领用单 id；原单只读保留。 */
    private Long resubmittedOfId;
    private String requestId;
    private String remark;

    public String getReqNo() { return reqNo; }
    public void setReqNo(String reqNo) { this.reqNo = reqNo; }
    public Long getPondId() { return pondId; }
    public void setPondId(Long pondId) { this.pondId = pondId; }
    public Long getObservationId() { return observationId; }
    public void setObservationId(Long observationId) { this.observationId = observationId; }
    public String getDiseaseDesc() { return diseaseDesc; }
    public void setDiseaseDesc(String diseaseDesc) { this.diseaseDesc = diseaseDesc; }
    public LocalDate getUseDate() { return useDate; }
    public void setUseDate(LocalDate useDate) { this.useDate = useDate; }
    public BigDecimal getWaterVolumeM3() { return waterVolumeM3; }
    public void setWaterVolumeM3(BigDecimal waterVolumeM3) { this.waterVolumeM3 = waterVolumeM3; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApplicant() { return applicant; }
    public void setApplicant(String applicant) { this.applicant = applicant; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public String getApproveRemark() { return approveRemark; }
    public void setApproveRemark(String approveRemark) { this.approveRemark = approveRemark; }
    public LocalDateTime getApprovedTime() { return approvedTime; }
    public void setApprovedTime(LocalDateTime approvedTime) { this.approvedTime = approvedTime; }
    public String getWithdrawBy() { return withdrawBy; }
    public void setWithdrawBy(String withdrawBy) { this.withdrawBy = withdrawBy; }
    public LocalDateTime getWithdrawTime() { return withdrawTime; }
    public void setWithdrawTime(LocalDateTime withdrawTime) { this.withdrawTime = withdrawTime; }
    public Integer getLinkVersion() { return linkVersion; }
    public void setLinkVersion(Integer linkVersion) { this.linkVersion = linkVersion; }
    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }
    public Long getResubmittedOfId() { return resubmittedOfId; }
    public void setResubmittedOfId(Long resubmittedOfId) { this.resubmittedOfId = resubmittedOfId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}

package com.evops.aqua.med.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.disease.entity.DiseaseObservation;
import com.evops.aqua.disease.mapper.DiseaseObservationMapper;
import com.evops.aqua.entity.Pond;
import com.evops.aqua.med.dto.RequisitionActionRequest;
import com.evops.aqua.med.dto.RequisitionCreateRequest;
import com.evops.aqua.med.dto.RequisitionItemRequest;
import com.evops.aqua.med.dto.RequisitionResubmitRequest;
import com.evops.aqua.med.dto.RequisitionReviewRequest;
import com.evops.aqua.med.entity.MedBatch;
import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.entity.MedDrug;
import com.evops.aqua.med.entity.MedRequisition;
import com.evops.aqua.med.entity.MedRequisitionItem;
import com.evops.aqua.med.entity.MedWithdrawalLock;
import com.evops.aqua.med.mapper.MedRequisitionItemMapper;
import com.evops.aqua.med.mapper.MedRequisitionMapper;
import com.evops.aqua.med.mapper.MedWithdrawalLockMapper;
import com.evops.aqua.service.PondService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用药领用单：关联池塘、病情（疫病观察单）与药品批号。
 * 创建时按剂量×水体（面积×水深）估算用量并冻结剂量/休药期/批次行级上下文快照；
 * 提交后按"本行用药日快照 + 本行休药期"计算休药期并锁定预计出池日（多药叠加取最晚结束日）；
 * 库存扣减与领用审核同一事务；撤回只能撤销未审核单；撤回后重提生成新版本单，历史审核结论不重算。
 */
@Service
public class MedRequisitionService {

    /** 角色视图：兽医看处方明细，仓管只看库存字段。 */
    public static final String ROLE_VET = "VET";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 编号版本后缀：MR-池-日期-序号V2；重提逐版递增，人眼可辨。 */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("^(.*?)V(\\d+)$");

    private final MedRequisitionMapper requisitionMapper;
    private final MedRequisitionItemMapper itemMapper;
    private final MedWithdrawalLockMapper lockMapper;
    private final MedDrugService drugService;
    private final MedBatchService batchService;
    private final PondService pondService;
    private final DiseaseObservationMapper observationMapper;
    private final MedEvidenceService evidenceService;
    private final MedConcurrencyGuard concurrencyGuard;

    public MedRequisitionService(MedRequisitionMapper requisitionMapper,
                                 MedRequisitionItemMapper itemMapper,
                                 MedWithdrawalLockMapper lockMapper,
                                 MedDrugService drugService,
                                 MedBatchService batchService,
                                 PondService pondService,
                                 DiseaseObservationMapper observationMapper,
                                 MedEvidenceService evidenceService,
                                 MedConcurrencyGuard concurrencyGuard) {
        this.requisitionMapper = requisitionMapper;
        this.itemMapper = itemMapper;
        this.lockMapper = lockMapper;
        this.drugService = drugService;
        this.batchService = batchService;
        this.pondService = pondService;
        this.observationMapper = observationMapper;
        this.evidenceService = evidenceService;
        this.concurrencyGuard = concurrencyGuard;
    }

    /**
     * 创建领用草稿：估算用量 = 剂量 × 水体；剂量/休药期/批号上下文逐行冻结快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public MedRequisition create(RequisitionCreateRequest request) {
        MedRequisition duplicated = requisitionMapper.selectOne(new QueryWrapper<MedRequisition>()
                .eq("request_id", request.getRequestId()).last("limit 1"));
        if (duplicated != null) {
            return duplicated;
        }
        Pond pond = pondService.getOrThrow(request.getPondId());
        pondService.requireActive(pond);
        if (request.getObservationId() != null) {
            DiseaseObservation observation = observationMapper.selectById(request.getObservationId());
            if (observation == null) {
                throw new IllegalArgumentException("病情观察单不存在，id=" + request.getObservationId());
            }
            if (!observation.getPondId().equals(pond.getId())) {
                throw new IllegalArgumentException("病情观察单" + observation.getObsNo()
                        + "属于池塘id=" + observation.getPondId() + "，与领用池塘id=" + pond.getId() + "不一致");
            }
        }
        BigDecimal waterVolume = waterVolumeOf(pond);

        MedRequisition requisition = new MedRequisition();
        requisition.setReqNo(nextReqNo(pond.getId(), request.getUseDate()));
        requisition.setPondId(pond.getId());
        requisition.setObservationId(request.getObservationId());
        requisition.setDiseaseDesc(request.getDiseaseDesc());
        requisition.setUseDate(request.getUseDate());
        requisition.setWaterVolumeM3(waterVolume);
        requisition.setStatus(MedRequisition.STATUS_DRAFT);
        requisition.setApplicant(request.getApplicant());
        requisition.setLinkVersion(1);
        requisition.setRequestId(request.getRequestId());
        requisition.setRemark(request.getRemark());
        requisitionMapper.insert(requisition);

        List<MedRequisitionItem> items = new ArrayList<>();
        for (RequisitionItemRequest line : request.getItems()) {
            items.add(buildItem(requisition, waterVolume, line.getDrugId(), line.getMedBatchId(),
                    line.getDosagePerM3(), 1));
        }
        evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, requisition.getId(),
                MedChangeEvidence.ACTION_REQ_CREATE, request.getRequestId(), request.getApplicant(),
                null, requisition, "创建领用草稿：" + requisition.getReqNo() + "，明细" + items.size() + "行");
        return requisition;
    }

    /**
     * 提交：草稿转待审核，逐明细按本行快照计算休药结束日并重算池塘出池锁定。
     * 同池并发提交/撤回串行裁决：先到者生效，后到者被拒绝并指认生效请求；跨池互不阻塞。
     */
    @Transactional(rollbackFor = Exception.class)
    public MedRequisition submit(Long id, RequisitionActionRequest request) {
        MedRequisition requisition = getOrThrow(id);
        if (MedRequisition.STATUS_PENDING.equals(requisition.getStatus())
                || alreadyDone(MedChangeEvidence.ACTION_REQ_SUBMIT, id, request.getRequestId())) {
            return requisition;
        }
        // 先拿池锁再校验状态：并发对手持锁改状态时，本请求按并发被拒裁决，而不是读到旧状态做普通校验
        MedConcurrencyGuard.LeasedLock lease = concurrencyGuard.tryAcquire(
                requisition.getPondId(), MedChangeEvidence.ACTION_REQ_SUBMIT,
                request.getRequestId(), request.getOperator());
        try {
            requisition = getOrThrow(id);
            if (MedRequisition.STATUS_PENDING.equals(requisition.getStatus())
                    || alreadyDone(MedChangeEvidence.ACTION_REQ_SUBMIT, id, request.getRequestId())) {
                return requisition;
            }
            if (!MedRequisition.STATUS_DRAFT.equals(requisition.getStatus())) {
                throw new IllegalArgumentException("仅草稿状态可提交，当前状态：" + requisition.getStatus());
            }
            MedRequisition before = copyOf(requisition);
            List<MedRequisitionItem> items = itemsOf(id);
            for (MedRequisitionItem item : items) {
                item.setWithdrawalEndDate(withdrawalEndDateOf(requisition, item));
                itemMapper.updateById(item);
            }
            requisition.setStatus(MedRequisition.STATUS_PENDING);
            requisition.setSubmitTime(LocalDateTime.now());
            requisitionMapper.updateById(requisition);
            concurrencyGuard.recordEffective(lease, MedChangeEvidence.ACTION_REQ_SUBMIT);
            recomputeLock(requisition.getPondId(), request.getOperator(), request.getRequestId());
            evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, id,
                    MedChangeEvidence.ACTION_REQ_SUBMIT, request.getRequestId(), request.getOperator(),
                    before, requisition, "提交领用单，按本行批号/用药日快照计算休药期并锁定预计出池日");
            return requisition;
        } finally {
            concurrencyGuard.release(lease);
        }
    }

    /**
     * 审核通过：库存扣减与审核同一事务——任一明细库存不足则整体回滚，
     * 领用单停留待审核，失败原因独立事务留证。审核不重算休药期（提交时已冻结）。
     */
    @Transactional(rollbackFor = Exception.class)
    public MedRequisition approve(Long id, RequisitionReviewRequest request) {
        MedRequisition requisition = getOrThrow(id);
        if (MedRequisition.STATUS_APPROVED.equals(requisition.getStatus())
                || alreadyDone(MedChangeEvidence.ACTION_REQ_APPROVE, id, request.getRequestId())) {
            return requisition;
        }
        // 先拿池锁再校验状态：并发撤回/驳回持锁改状态时，审核按并发被拒裁决
        MedConcurrencyGuard.LeasedLock lease = concurrencyGuard.tryAcquire(
                requisition.getPondId(), MedChangeEvidence.ACTION_REQ_APPROVE,
                request.getRequestId(), request.getApprovedBy());
        try {
            requisition = getOrThrow(id);
            if (MedRequisition.STATUS_APPROVED.equals(requisition.getStatus())
                    || alreadyDone(MedChangeEvidence.ACTION_REQ_APPROVE, id, request.getRequestId())) {
                return requisition;
            }
            if (!MedRequisition.STATUS_PENDING.equals(requisition.getStatus())) {
                throw new IllegalArgumentException("仅待审核单可审核通过，当前状态：" + requisition.getStatus());
            }
            List<MedRequisitionItem> items = itemsOf(id);
            List<String> conflicts = new ArrayList<>();
            Map<Long, MedBatch> batches = new HashMap<>();
            for (MedRequisitionItem item : items) {
                MedBatch batch = batchService.getOrThrow(item.getMedBatchId());
                batches.put(item.getId(), batch);
                if (batch.availableQuantity().compareTo(item.getEstimatedQty()) < 0) {
                    conflicts.add("批号" + batch.getBatchNo() + "可用库存不足：可用"
                            + batch.availableQuantity() + "，需扣减" + item.getEstimatedQty());
                }
            }
            if (!conflicts.isEmpty()) {
                String detail = "审核失败（库存扣减与领用审核需一致，整体停留待审核）：" + String.join("；", conflicts);
                evidenceService.recordStandalone(MedChangeEvidence.ENTITY_REQUISITION, id,
                        MedChangeEvidence.ACTION_REQ_APPROVE_FAILED, request.getRequestId(),
                        request.getApprovedBy(), requisition, null, detail);
                throw new IllegalArgumentException(detail);
            }
            MedRequisition before = copyOf(requisition);
            for (MedRequisitionItem item : items) {
                batchService.deduct(batches.get(item.getId()), item.getEstimatedQty(),
                        request.getApprovedBy(), request.getRequestId(),
                        "领用审核通过扣减：领用单" + requisition.getReqNo());
            }
            requisition.setStatus(MedRequisition.STATUS_APPROVED);
            requisition.setApprovedBy(request.getApprovedBy());
            requisition.setApproveRemark(request.getApproveRemark());
            requisition.setApprovedTime(LocalDateTime.now());
            requisitionMapper.updateById(requisition);
            concurrencyGuard.recordEffective(lease, MedChangeEvidence.ACTION_REQ_APPROVE);
            evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, id,
                    MedChangeEvidence.ACTION_REQ_APPROVE, request.getRequestId(), request.getApprovedBy(),
                    before, requisition, "审核通过，库存同步扣减");
            return requisition;
        } finally {
            concurrencyGuard.release(lease);
        }
    }

    /**
     * 审核驳回：退回并释放该单对池塘的休药锁定。驳回结论一旦形成不可重提、不静默重算。
     */
    @Transactional(rollbackFor = Exception.class)
    public MedRequisition reject(Long id, RequisitionReviewRequest request) {
        MedRequisition requisition = getOrThrow(id);
        if (MedRequisition.STATUS_REJECTED.equals(requisition.getStatus())
                || alreadyDone(MedChangeEvidence.ACTION_REQ_REJECT, id, request.getRequestId())) {
            return requisition;
        }
        // 先拿池锁再校验状态：并发撤回/审核持锁改状态时，驳回按并发被拒裁决
        MedConcurrencyGuard.LeasedLock lease = concurrencyGuard.tryAcquire(
                requisition.getPondId(), MedChangeEvidence.ACTION_REQ_REJECT,
                request.getRequestId(), request.getApprovedBy());
        try {
            requisition = getOrThrow(id);
            if (MedRequisition.STATUS_REJECTED.equals(requisition.getStatus())
                    || alreadyDone(MedChangeEvidence.ACTION_REQ_REJECT, id, request.getRequestId())) {
                return requisition;
            }
            if (!MedRequisition.STATUS_PENDING.equals(requisition.getStatus())) {
                throw new IllegalArgumentException("仅待审核单可驳回，当前状态：" + requisition.getStatus());
            }
            MedRequisition before = copyOf(requisition);
            requisition.setStatus(MedRequisition.STATUS_REJECTED);
            requisition.setApprovedBy(request.getApprovedBy());
            requisition.setApproveRemark(request.getApproveRemark());
            requisition.setApprovedTime(LocalDateTime.now());
            requisitionMapper.updateById(requisition);
            concurrencyGuard.recordEffective(lease, MedChangeEvidence.ACTION_REQ_REJECT);
            recomputeLock(requisition.getPondId(), request.getApprovedBy(), request.getRequestId());
            evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, id,
                    MedChangeEvidence.ACTION_REQ_REJECT, request.getRequestId(), request.getApprovedBy(),
                    before, requisition, "审核驳回：" + request.getApproveRemark());
            return requisition;
        } finally {
            concurrencyGuard.release(lease);
        }
    }

    /**
     * 撤回：只能撤销未审核单（草稿/待审核）；已审核单一律拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public MedRequisition withdraw(Long id, RequisitionActionRequest request) {
        MedRequisition requisition = getOrThrow(id);
        if (MedRequisition.STATUS_WITHDRAWN.equals(requisition.getStatus())
                || alreadyDone(MedChangeEvidence.ACTION_REQ_WITHDRAW, id, request.getRequestId())) {
            return requisition;
        }
        // 先拿池锁再校验状态：并发审核/驳回持锁改状态时，撤回按并发被拒裁决，绝不翻转已审核结论
        MedConcurrencyGuard.LeasedLock lease = concurrencyGuard.tryAcquire(
                requisition.getPondId(), MedChangeEvidence.ACTION_REQ_WITHDRAW,
                request.getRequestId(), request.getOperator());
        try {
            requisition = getOrThrow(id);
            if (MedRequisition.STATUS_WITHDRAWN.equals(requisition.getStatus())
                    || alreadyDone(MedChangeEvidence.ACTION_REQ_WITHDRAW, id, request.getRequestId())) {
                return requisition;
            }
            boolean unreviewed = MedRequisition.STATUS_DRAFT.equals(requisition.getStatus())
                    || MedRequisition.STATUS_PENDING.equals(requisition.getStatus());
            if (!unreviewed) {
                throw new IllegalArgumentException("已审核单不可撤回（当前状态：" + requisition.getStatus()
                        + "），撤回只能撤销未审核单");
            }
            MedRequisition before = copyOf(requisition);
            requisition.setStatus(MedRequisition.STATUS_WITHDRAWN);
            requisition.setWithdrawBy(request.getOperator());
            requisition.setWithdrawTime(LocalDateTime.now());
            requisitionMapper.updateById(requisition);
            concurrencyGuard.recordEffective(lease, MedChangeEvidence.ACTION_REQ_WITHDRAW);
            recomputeLock(requisition.getPondId(), request.getOperator(), request.getRequestId());
            evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, id,
                    MedChangeEvidence.ACTION_REQ_WITHDRAW, request.getRequestId(), request.getOperator(),
                    before, requisition, "撤回未审核领用单，释放休药锁定");
            return requisition;
        } finally {
            concurrencyGuard.release(lease);
        }
    }

    /**
     * 撤回重提：对已撤回领用单按同一批数据重新提交，生成新版本领用单。
     * 原撤回单只读保留（审核历史、撤回人/时间均不回改），新单编号带 Vn、批次关联版本递增、
     * 提交时间新写入——用药领用编号、批次关联版本和时间三者均可辨。
     * 剂量/休药期沿用原行冻结快照，批号重新校验在库与效期；新单直接进入待审核并重算本池锁定。
     */
    @Transactional(rollbackFor = Exception.class)
    public MedRequisition resubmit(Long id, RequisitionResubmitRequest request) {
        MedRequisition origin = getOrThrow(id);
        MedRequisition duplicated = requisitionMapper.selectOne(new QueryWrapper<MedRequisition>()
                .eq("request_id", request.getRequestId()).last("limit 1"));
        if (duplicated != null) {
            return duplicated;
        }
        if (!MedRequisition.STATUS_WITHDRAWN.equals(origin.getStatus())) {
            throw new IllegalArgumentException("仅已撤回单可撤回重提，当前状态：" + origin.getStatus()
                    + "；已审核/已驳回结论为历史事实，不允许重提或静默重算");
        }
        Pond pond = pondService.getOrThrow(origin.getPondId());
        pondService.requireActive(pond);
        MedConcurrencyGuard.LeasedLock lease = concurrencyGuard.tryAcquire(
                origin.getPondId(), MedChangeEvidence.ACTION_REQ_RESUBMIT,
                request.getRequestId(), request.getOperator());
        try {
            Long renewedCount = requisitionMapper.selectCount(new QueryWrapper<MedRequisition>()
                    .eq("resubmitted_of_id", origin.getId()));
            if (renewedCount != null && renewedCount > 0) {
                throw new IllegalArgumentException("领用单" + origin.getReqNo()
                        + "已重提出新版本，不能重复重提；如需在新版本上继续操作请使用新单编号"
                        + "（同 requestId 重放返回首次重提结果）");
            }
            int newVersion = origin.getLinkVersion() == null ? 2 : origin.getLinkVersion() + 1;

            MedRequisition renewed = new MedRequisition();
            renewed.setReqNo(versionedReqNo(origin.getReqNo(), newVersion));
            renewed.setPondId(origin.getPondId());
            renewed.setObservationId(origin.getObservationId());
            renewed.setDiseaseDesc(origin.getDiseaseDesc());
            renewed.setUseDate(origin.getUseDate());
            renewed.setWaterVolumeM3(origin.getWaterVolumeM3());
            renewed.setStatus(MedRequisition.STATUS_PENDING);
            renewed.setApplicant(origin.getApplicant());
            renewed.setLinkVersion(newVersion);
            renewed.setSubmitTime(LocalDateTime.now());
            renewed.setResubmittedOfId(origin.getId());
            renewed.setRequestId(request.getRequestId());
            renewed.setRemark(origin.getRemark());
            requisitionMapper.insert(renewed);

            List<MedRequisitionItem> originItems = itemsOf(id);
            List<MedRequisitionItem> newItems = new ArrayList<>();
            for (MedRequisitionItem originItem : originItems) {
                // 批号重新校验（仍在库、未过期、药品仍启用）；剂量/休药期沿用原处方冻结值
                MedDrug drug = drugService.getOrThrow(originItem.getDrugId());
                drugService.requireActive(drug);
                MedBatch batch = batchService.getOrThrow(originItem.getMedBatchId());
                if (!MedBatch.STATUS_IN_STOCK.equals(batch.getStatus())) {
                    throw new IllegalArgumentException("批号" + batch.getBatchNo() + "已用尽，不能重提领用");
                }
                if (batch.getExpiryDate() != null && batch.getExpiryDate().isBefore(renewed.getUseDate())) {
                    throw new IllegalArgumentException("批号" + batch.getBatchNo() + "在用药日前已过期（有效期至"
                            + batch.getExpiryDate() + "），不能重提领用");
                }
                MedRequisitionItem item = new MedRequisitionItem();
                item.setRequisitionId(renewed.getId());
                item.setDrugId(drug.getId());
                item.setMedBatchId(batch.getId());
                item.setDosagePerM3(originItem.getDosagePerM3());
                item.setEstimatedQty(originItem.getDosagePerM3()
                        .multiply(renewed.getWaterVolumeM3()).setScale(2, RoundingMode.HALF_UP));
                item.setWithdrawalDays(originItem.getWithdrawalDays());
                item.setPondId(renewed.getPondId());
                item.setUseDateSnapshot(renewed.getUseDate());
                item.setBatchNo(batch.getBatchNo());
                item.setDrugName(drug.getDrugName());
                item.setUnit(drug.getUnit());
                item.setBatchVersion(newVersion);
                item.setWithdrawalEndDate(withdrawalEndDateOf(renewed, item));
                itemMapper.insert(item);
                newItems.add(item);
            }

            concurrencyGuard.recordEffective(lease, MedChangeEvidence.ACTION_REQ_RESUBMIT);
            recomputeLock(renewed.getPondId(), request.getOperator(), request.getRequestId());

            // 新单自身保留完整创建→提交证据链；原单留重提证据指认新单，原审核历史原样保留
            evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, renewed.getId(),
                    MedChangeEvidence.ACTION_REQ_CREATE, request.getRequestId(), request.getOperator(),
                    null, renewed, "撤回重提创建新版本领用单：" + renewed.getReqNo()
                            + "（原单" + origin.getReqNo() + "），明细" + newItems.size() + "行");
            evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, renewed.getId(),
                    MedChangeEvidence.ACTION_REQ_SUBMIT, request.getRequestId(), request.getOperator(),
                    null, renewed, "撤回重提即提交待审核，按本行批号/用药日快照计算休药期");
            evidenceService.record(MedChangeEvidence.ENTITY_REQUISITION, id,
                    MedChangeEvidence.ACTION_REQ_RESUBMIT, request.getRequestId(), request.getOperator(),
                    origin, renewed, "撤回重提：原单" + origin.getReqNo() + "只读保留，新版本"
                            + renewed.getReqNo() + "（批次关联版本V" + newVersion + "）进入待审核");
            return renewed;
        } finally {
            concurrencyGuard.release(lease);
        }
    }

    /**
     * 角色视图：VET（兽医）看处方明细（病情/剂量/休药期）；
     * 其他角色（含仓管 KEEPER）只看库存字段（批号/数量/效期），看不到处方信息。
     */
    public Map<String, Object> view(Long id, String role) {
        MedRequisition requisition = getOrThrow(id);
        List<MedRequisitionItem> items = itemsOf(id);
        boolean vet = ROLE_VET.equalsIgnoreCase(role == null ? "" : role.trim());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reqNo", requisition.getReqNo());
        view.put("status", requisition.getStatus());
        view.put("useDate", requisition.getUseDate());
        view.put("linkVersion", requisition.getLinkVersion());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (MedRequisitionItem item : items) {
            MedDrug drug = drugService.getOrThrow(item.getDrugId());
            MedBatch batch = batchService.getOrThrow(item.getMedBatchId());
            Map<String, Object> line = new LinkedHashMap<>();
            // 批号优先展示行级冻结快照，保证跨池同药多批次时每行只认自己的批次上下文
            line.put("drugName", item.getDrugName() != null ? item.getDrugName() : drug.getDrugName());
            line.put("batchNo", item.getBatchNo() != null ? item.getBatchNo() : batch.getBatchNo());
            line.put("estimatedQty", item.getEstimatedQty());
            line.put("unit", item.getUnit() != null ? item.getUnit() : drug.getUnit());
            line.put("expiryDate", batch.getExpiryDate());
            if (vet) {
                line.put("pondId", item.getPondId());
                line.put("useDateSnapshot", item.getUseDateSnapshot());
                line.put("batchVersion", item.getBatchVersion());
                line.put("dosagePerM3", item.getDosagePerM3());
                line.put("withdrawalDays", item.getWithdrawalDays());
                line.put("withdrawalEndDate", item.getWithdrawalEndDate());
            }
            lines.add(line);
        }
        view.put("items", lines);
        if (vet) {
            view.put("pondId", requisition.getPondId());
            view.put("observationId", requisition.getObservationId());
            view.put("diseaseDesc", requisition.getDiseaseDesc());
            view.put("waterVolumeM3", requisition.getWaterVolumeM3());
            view.put("applicant", requisition.getApplicant());
            view.put("approvedBy", requisition.getApprovedBy());
            view.put("approveRemark", requisition.getApproveRemark());
            view.put("submitTime", requisition.getSubmitTime());
            view.put("withdrawBy", requisition.getWithdrawBy());
            view.put("withdrawTime", requisition.getWithdrawTime());
            view.put("resubmittedOfId", requisition.getResubmittedOfId());
        }
        return view;
    }

    public MedRequisition getOrThrow(Long id) {
        MedRequisition requisition = requisitionMapper.selectById(id);
        if (requisition == null) {
            throw new IllegalArgumentException("领用单不存在，id=" + id);
        }
        return requisition;
    }

    public List<MedRequisition> list(Long pondId, String status) {
        QueryWrapper<MedRequisition> wrapper = new QueryWrapper<>();
        wrapper.eq(pondId != null, "pond_id", pondId);
        wrapper.eq(status != null && !status.trim().isEmpty(), "status", status);
        wrapper.orderByAsc("id");
        return requisitionMapper.selectList(wrapper);
    }

    public List<MedRequisitionItem> itemsOf(Long requisitionId) {
        return itemMapper.selectList(new QueryWrapper<MedRequisitionItem>()
                .eq("requisition_id", requisitionId).orderByAsc("id"));
    }

    /**
     * 构建一行领用明细并冻结行级快照：所属池塘、用药日、批号编号、药品名、单位、批次关联版本。
     * 后续休药期/出池锁定重算只读本行快照，杜绝跨池同药把较早批次上下文套到另一批次。
     */
    private MedRequisitionItem buildItem(MedRequisition requisition, BigDecimal waterVolume,
                                         Long drugId, Long medBatchId, BigDecimal dosageOverride, int version) {
        MedDrug drug = drugService.getOrThrow(drugId);
        drugService.requireActive(drug);
        MedBatch batch = batchService.getOrThrow(medBatchId);
        if (!batch.getDrugId().equals(drug.getId())) {
            throw new IllegalArgumentException("批号" + batch.getBatchNo()
                    + "不属于药品" + drug.getDrugName());
        }
        if (!MedBatch.STATUS_IN_STOCK.equals(batch.getStatus())) {
            throw new IllegalArgumentException("批号" + batch.getBatchNo() + "已用尽，不能领用");
        }
        if (batch.getExpiryDate() != null && batch.getExpiryDate().isBefore(requisition.getUseDate())) {
            throw new IllegalArgumentException("批号" + batch.getBatchNo() + "在用药日前已过期（有效期至"
                    + batch.getExpiryDate() + "），不能领用");
        }
        BigDecimal dosage = dosageOverride != null ? dosageOverride : drug.getDosagePerM3();
        MedRequisitionItem item = new MedRequisitionItem();
        item.setRequisitionId(requisition.getId());
        item.setDrugId(drug.getId());
        item.setMedBatchId(batch.getId());
        item.setDosagePerM3(dosage);
        item.setEstimatedQty(dosage.multiply(waterVolume).setScale(2, RoundingMode.HALF_UP));
        item.setWithdrawalDays(drug.getWithdrawalDays());
        item.setPondId(requisition.getPondId());
        item.setUseDateSnapshot(requisition.getUseDate());
        item.setBatchNo(batch.getBatchNo());
        item.setDrugName(drug.getDrugName());
        item.setUnit(drug.getUnit());
        item.setBatchVersion(version);
        itemMapper.insert(item);
        return item;
    }

    /**
     * 休药结束日只能按"本单用药日 + 本行休药期快照"计算；
     * 行快照池塘/用药日与头单不一致即数据上下文被串用，直接拒绝而非将错就错。
     * 历史行（快照列升级前为 NULL）回退到头单上下文，保证旧数据可读。
     */
    private LocalDate withdrawalEndDateOf(MedRequisition requisition, MedRequisitionItem item) {
        if (item.getPondId() != null && !requisition.getPondId().equals(item.getPondId())) {
            throw new IllegalStateException("领用明细" + item.getId() + "批次关联池塘(id=" + item.getPondId()
                    + ")与领用单池塘(id=" + requisition.getPondId() + ")不一致，休药期上下文串用，拒绝计算");
        }
        LocalDate useDate = item.getUseDateSnapshot() != null
                ? item.getUseDateSnapshot() : requisition.getUseDate();
        if (item.getUseDateSnapshot() != null && !requisition.getUseDate().equals(useDate)) {
            throw new IllegalStateException("领用明细" + item.getId() + "用药日快照(" + useDate
                    + ")与领用单用药日(" + requisition.getUseDate() + ")不一致，休药期上下文串用，拒绝计算");
        }
        return useDate.plusDays(item.getWithdrawalDays());
    }

    /**
     * 重算池塘休药锁定：取该池在途（待审核/已审核）领用单各明细休药结束日的最晚值；
     * 只采纳本池行快照，无在途单则释放锁定。锁定行记录来源单/行/批号/药品，变化才写证据。
     */
    private void recomputeLock(Long pondId, String operator, String requestId) {
        List<MedRequisition> active = requisitionMapper.selectList(new QueryWrapper<MedRequisition>()
                .eq("pond_id", pondId)
                .in("status", MedRequisition.STATUS_PENDING, MedRequisition.STATUS_APPROVED)
                .orderByAsc("id"));
        LocalDate latest = null;
        MedRequisition latestReq = null;
        MedRequisitionItem latestItem = null;
        for (MedRequisition req : active) {
            for (MedRequisitionItem item : itemsOf(req.getId())) {
                if (item.getWithdrawalEndDate() == null) {
                    continue;
                }
                if (item.getPondId() != null && !pondId.equals(item.getPondId())) {
                    throw new IllegalStateException("领用单" + req.getReqNo() + "明细" + item.getId()
                            + "行快照池塘id=" + item.getPondId() + "不属于本池id=" + pondId
                            + "，跨池批次关联串用，拒绝重算休药锁定");
                }
                boolean later = latest == null || item.getWithdrawalEndDate().isAfter(latest);
                // 同日取最早行，保证多单并发重算结果确定、不会来回抖动
                boolean tieBreak = latest != null && item.getWithdrawalEndDate().equals(latest)
                        && latestItem != null && item.getId() < latestItem.getId();
                if (later || tieBreak) {
                    latest = item.getWithdrawalEndDate();
                    latestReq = req;
                    latestItem = item;
                }
            }
        }
        MedWithdrawalLock lock = lockMapper.selectOne(new QueryWrapper<MedWithdrawalLock>()
                .eq("pond_id", pondId).last("limit 1"));
        if (latest == null) {
            if (lock != null) {
                lockMapper.deleteById(lock.getId());
                evidenceService.record(MedChangeEvidence.ENTITY_WITHDRAWAL_LOCK, lock.getId(),
                        MedChangeEvidence.ACTION_LOCK_RELEASE, requestId, operator, lock, null,
                        "池塘id=" + pondId + "无在途领用单，释放休药锁定");
            }
            return;
        }
        String detail = "最晚休药结束日来自领用单" + latestReq.getReqNo() + "第" + latestItem.getId()
                + "行（药品" + latestItem.getDrugName() + "/批号" + latestItem.getBatchNo()
                + "，用药日" + latestItem.getUseDateSnapshot() + "+休药" + latestItem.getWithdrawalDays()
                + "天，多药叠加取最晚）";
        boolean same = lock != null
                && latest.equals(lock.getLockedUntil())
                && latestReq.getId().equals(lock.getSourceReqId())
                && latestItem.getId().equals(lock.getSourceItemId());
        if (same) {
            return; // 锁定日期与来源均未变化：不写证据，历史审核/锁定结论不静默重算
        }
        if (lock == null) {
            lock = new MedWithdrawalLock();
            lock.setPondId(pondId);
            applyLockSource(lock, latest, latestReq, latestItem, detail);
            lockMapper.insert(lock);
            evidenceService.record(MedChangeEvidence.ENTITY_WITHDRAWAL_LOCK, lock.getId(),
                    MedChangeEvidence.ACTION_LOCK_UPDATE, requestId, operator, null, lock,
                    "池塘id=" + pondId + "锁定预计出池日不早于" + latest);
        } else {
            MedWithdrawalLock before = new MedWithdrawalLock();
            org.springframework.beans.BeanUtils.copyProperties(lock, before);
            applyLockSource(lock, latest, latestReq, latestItem, detail);
            lockMapper.updateById(lock);
            evidenceService.record(MedChangeEvidence.ENTITY_WITHDRAWAL_LOCK, lock.getId(),
                    MedChangeEvidence.ACTION_LOCK_UPDATE, requestId, operator, before, lock,
                    "池塘id=" + pondId + "休药锁定调整为" + latest);
        }
    }

    private void applyLockSource(MedWithdrawalLock lock, LocalDate latest,
                                 MedRequisition req, MedRequisitionItem item, String detail) {
        lock.setLockedUntil(latest);
        lock.setDetail(detail);
        lock.setSourceReqId(req.getId());
        lock.setSourceReqNo(req.getReqNo());
        lock.setSourceItemId(item.getId());
        lock.setSourceBatchNo(item.getBatchNo());
        lock.setSourceDrugName(item.getDrugName());
    }

    private boolean alreadyDone(String action, Long id, String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return false;
        }
        return evidenceService.list(MedChangeEvidence.ENTITY_REQUISITION, id, requestId)
                .stream().anyMatch(e -> action.equals(e.getAction()));
    }

    private BigDecimal waterVolumeOf(Pond pond) {
        if (pond.getWaterAreaM2() == null || pond.getWaterDepthM() == null) {
            throw new IllegalArgumentException("池塘" + pond.getPondCode()
                    + "缺少水面面积或水深档案，无法按水体估算用药量，请先完善池塘档案");
        }
        return pond.getWaterAreaM2().multiply(pond.getWaterDepthM()).setScale(2, RoundingMode.HALF_UP);
    }

    private String nextReqNo(Long pondId, LocalDate useDate) {
        Long count = requisitionMapper.selectCount(new QueryWrapper<MedRequisition>()
                .eq("pond_id", pondId).eq("use_date", useDate));
        return "MR-" + pondId + "-" + useDate.format(DAY) + "-" + (count + 1);
    }

    /**
     * 版本编号：剥离原编号已有 Vn 后缀后挂新版本号——
     * MR-1-20261010-1 → MR-1-20261010-1V2；对 V2 单再重提 → V3。
     */
    private String versionedReqNo(String originReqNo, int version) {
        Matcher matcher = VERSION_SUFFIX.matcher(originReqNo);
        String base = matcher.matches() ? matcher.group(1) : originReqNo;
        return base + "V" + version;
    }

    private MedRequisition copyOf(MedRequisition requisition) {
        MedRequisition copy = new MedRequisition();
        org.springframework.beans.BeanUtils.copyProperties(requisition, copy);
        return copy;
    }
}

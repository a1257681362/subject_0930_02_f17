package com.evops.aqua.med;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.med.entity.MedBatch;
import com.evops.aqua.med.entity.MedBizLock;
import com.evops.aqua.med.entity.MedChangeEvidence;
import com.evops.aqua.med.entity.MedConcurrentAudit;
import com.evops.aqua.med.entity.MedRequisition;
import com.evops.aqua.med.entity.MedRequisitionItem;
import com.evops.aqua.med.entity.MedWithdrawalLock;
import com.evops.aqua.med.mapper.MedBatchMapper;
import com.evops.aqua.med.mapper.MedBizLockMapper;
import com.evops.aqua.med.mapper.MedChangeEvidenceMapper;
import com.evops.aqua.med.mapper.MedConcurrentAuditMapper;
import com.evops.aqua.med.mapper.MedRequisitionItemMapper;
import com.evops.aqua.med.mapper.MedRequisitionMapper;
import com.evops.aqua.med.mapper.MedWithdrawalLockMapper;
import com.evops.aqua.med.service.MedConcurrencyGuard;
import com.evops.aqua.med.service.MedConcurrentRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 用药领用批次关联修复验收（aqua-17）。
 * 主线：同一药品被两个池塘领用不同批号 → 行级快照各算各的休药期，较早批次不套另一批次；
 * 多药叠加取最晚结束日，撤回一单后锁定回到剩余最晚来源行；
 * 同池提交/审核/撤回库级裁决（先生效者成、后到者被拒并指认对方，双方留审计），跨池并发互不阻塞；
 * 撤回再重提：领用编号（Vn）、批次关联版本（递增）、提交时间（新写入）三者可辨，原撤回单只读保留；
 * 已审核/已驳回为历史事实，不允许重提或静默重算；行快照池塘被改串时提交直接拒绝。
 *
 * 独立内存库：原 aqua-6 验收按全表计数证据，本用例使用独立数据源避免互相污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:evops-med-aqua17;DB_CLOSE_DELAY=-1")
class MedRequisitionConcurrencyTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MedRequisitionMapper requisitionMapper;
    @Autowired
    private MedRequisitionItemMapper itemMapper;
    @Autowired
    private MedBatchMapper medBatchMapper;
    @Autowired
    private MedWithdrawalLockMapper lockMapper;
    @Autowired
    private MedBizLockMapper bizLockMapper;
    @Autowired
    private MedConcurrentAuditMapper concurrentAuditMapper;
    @Autowired
    private MedChangeEvidenceMapper evidenceMapper;
    @Autowired
    private MedConcurrencyGuard concurrencyGuard;

    private final List<String> report = new ArrayList<>();
    private final Map<String, ResponseEntity<String>> lastResponses = new HashMap<>();
    private String sessionCookie;

    private Long pondA;      // 2000㎡ × 1.5m = 3000m³
    private Long pondB;      // 1000㎡ × 2.0m = 2000m³
    private Long pondC;      // 500㎡ × 1.0m = 500m³
    private Long pondD;      // 800㎡ × 1.0m = 800m³
    private Long pondE;      // 400㎡ × 1.0m = 400m³
    private Long drugA;      // 剂量2 g/m³，休药20天
    private Long drugC;      // 剂量1 g/m³，休药50天
    private Long batchOld;   // AA-2025-OLD 同药早批次（A池领用）
    private Long batchNew;   // AA-2026-NEW 同药另一批次（B池领用）
    private Long batchC;     // CC-2026-01
    private Long batchRace;  // AA-RACE C/D池领用
    private Long batchE;     // AA-POOL-E E池领用
    private Long reqOldId;
    private Long reqNewId;

    @Test
    void acceptanceCrossPondBatchContextAndResubmit() {
        checkpoint("CP0", "登录认证", () -> {
            Map<String, Object> body = new HashMap<>();
            body.put("username", "bootstrap");
            body.put("password", "bootstrap");
            ResponseEntity<String> resp = call(HttpMethod.POST, "/api/auth/login", body);
            assertSuccess(json(resp), "登录");
            List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
            check(cookies != null && !cookies.isEmpty(), "登录成功但未下发会话Cookie");
            sessionCookie = cookies.get(0).split(";")[0];
        });

        checkpoint("CP1", "建五口池；同药两批次（早批次/另一批次）+晚结束药；批号互不影响", () -> {
            pondA = createPond("MED-X1", "跨池一号池", "2000", "1.50");
            pondB = createPond("MED-X2", "跨池二号池", "1000", "2.00");
            pondC = createPond("MED-X3", "并发三号池", "500", "1.00");
            pondD = createPond("MED-X4", "裁决四号池", "800", "1.00");
            pondE = createPond("MED-X5", "串用防御五号池", "400", "1.00");

            JsonNode a = json(call(HttpMethod.POST, "/api/aqua/med/drugs",
                    drugBody("DRUG-XA", "恩诺沙星", "2", "20", "xdrug-a")));
            assertSuccess(a, "登记药品A(休药20天)");
            drugA = a.path("data").path("id").asLong();
            JsonNode c = json(call(HttpMethod.POST, "/api/aqua/med/drugs",
                    drugBody("DRUG-XC", "磺胺间甲氧嘧啶", "1", "50", "xdrug-c")));
            assertSuccess(c, "登记药品C(休药50天)");
            drugC = c.path("data").path("id").asLong();

            batchOld = stockInBatch("AA-2025-OLD", drugA, "10000", "xb-in-old");
            batchNew = stockInBatch("AA-2026-NEW", drugA, "10000", "xb-in-new");
            batchC = stockInBatch("CC-2026-01", drugC, "10000", "xb-in-c");
            batchRace = stockInBatch("AA-RACE-01", drugA, "10000", "xb-in-race");
            batchE = stockInBatch("AA-POOL-E1", drugA, "10000", "xb-in-e");
        });

        checkpoint("CP2", "同一药品两个池塘领用不同批号、不同用药日：行级快照各算各的，早批次不串另一批次", () -> {
            JsonNode oldReq = json(call(HttpMethod.POST, "/api/aqua/med/requisitions",
                    reqBody(pondA, "2026-10-10", "xreq-old", item(drugA, batchOld, null))));
            assertSuccess(oldReq, "一号池领用早批次");
            reqOldId = oldReq.path("data").path("id").asLong();
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + reqOldId + "/submit",
                    actionBody("xsubmit-old"))), "提交一号池单");

            JsonNode newReq = json(call(HttpMethod.POST, "/api/aqua/med/requisitions",
                    reqBody(pondB, "2026-10-12", "xreq-new", item(drugA, batchNew, null))));
            assertSuccess(newReq, "二号池领用另一批次");
            reqNewId = newReq.path("data").path("id").asLong();
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + reqNewId + "/submit",
                    actionBody("xsubmit-new"))), "提交二号池单");

            MedRequisitionItem oldItem = soleItem(reqOldId);
            check("AA-2025-OLD".equals(oldItem.getBatchNo()) && pondA.equals(oldItem.getPondId())
                            && "2026-10-10".equals(oldItem.getUseDateSnapshot().toString())
                            && oldItem.getBatchVersion() == 1
                            && "2026-10-30".equals(oldItem.getWithdrawalEndDate().toString()),
                    "一号池明细应冻结早批次/本池/10-10/V1，结束日10-30，实际=" + describe(oldItem));
            MedRequisitionItem newItem = soleItem(reqNewId);
            check("AA-2026-NEW".equals(newItem.getBatchNo()) && pondB.equals(newItem.getPondId())
                            && "2026-10-12".equals(newItem.getUseDateSnapshot().toString())
                            && newItem.getBatchVersion() == 1
                            && "2026-11-01".equals(newItem.getWithdrawalEndDate().toString()),
                    "二号池明细应冻结另一批次/本池/10-12/V1，结束日11-01，实际=" + describe(newItem));

            MedWithdrawalLock lockA = lockOf(pondA);
            MedWithdrawalLock lockB = lockOf(pondB);
            check(lockA != null && "2026-10-30".equals(lockA.getLockedUntil().toString())
                            && "AA-2025-OLD".equals(lockA.getSourceBatchNo())
                            && reqOldId.equals(lockA.getSourceReqId()),
                    "一号池锁应来自早批次10-30，实际=" + summarize(lockA));
            check(lockB != null && "2026-11-01".equals(lockB.getLockedUntil().toString())
                            && "AA-2026-NEW".equals(lockB.getSourceBatchNo())
                            && reqNewId.equals(lockB.getSourceReqId()),
                    "二号池不得被早批次提前，应来自另一批次11-01，实际=" + summarize(lockB));
        });

        checkpoint("CP3", "多药叠加取最晚结束日；撤回晚结束药后锁定回到剩余最晚来源行", () -> {
            JsonNode lateReq = json(call(HttpMethod.POST, "/api/aqua/med/requisitions",
                    reqBody(pondB, "2026-10-12", "xreq-late", item(drugC, batchC, null))));
            assertSuccess(lateReq, "二号池加领晚结束药C");
            Long reqLate = lateReq.path("data").path("id").asLong();
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + reqLate + "/submit",
                    actionBody("xsubmit-late"))), "提交晚结束药单");

            MedWithdrawalLock lockB = lockOf(pondB);
            check(lockB != null && "2026-12-01".equals(lockB.getLockedUntil().toString())
                            && "CC-2026-01".equals(lockB.getSourceBatchNo())
                            && reqLate.equals(lockB.getSourceReqId()),
                    "多药叠加应取药C最晚12-01，实际=" + summarize(lockB));

            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + reqLate + "/withdraw",
                    actionBody("xwd-late"))), "撤回晚结束药单");
            MedWithdrawalLock reverted = lockOf(pondB);
            check(reverted != null && "2026-11-01".equals(reverted.getLockedUntil().toString())
                            && "AA-2026-NEW".equals(reverted.getSourceBatchNo())
                            && reqNewId.equals(reverted.getSourceReqId()),
                    "撤回药C后锁定应回到另一批次药A的11-01，实际=" + summarize(reverted));
            check(MedRequisition.STATUS_WITHDRAWN.equals(requisitionMapper.selectById(reqLate).getStatus()),
                    "被撤回单应保持已撤回，不被重算回待审核");
        });

        checkpoint("CP4", "跨池并发提交互不阻塞；同池并发裁决先生效者成、后到者被拒并留双向审计", () -> {
            // 真实双线程：不同池塘同时提交，各拿各的锁，均生效
            Long cross1 = draftOnly(pondC, "2026-10-10", "xreq-cross1");
            Long cross2 = draftOnly(pondD, "2026-10-10", "xreq-cross2");
            racePair(
                    () -> postSubmit(cross1, "申请人甲", "xsubmit-cross1"),
                    () -> postSubmit(cross2, "申请人乙", "xsubmit-cross2"));
            check(MedRequisition.STATUS_PENDING.equals(requisitionMapper.selectById(cross1).getStatus())
                            && MedRequisition.STATUS_PENDING.equals(requisitionMapper.selectById(cross2).getStatus()),
                    "跨池并发提交两单都应生效为待审核");
            MedConcurrentAudit eff1 = auditOf("xsubmit-cross1");
            MedConcurrentAudit eff2 = auditOf("xsubmit-cross2");
            check(eff1 != null && eff2 != null
                            && MedConcurrentAudit.VERDICT_EFFECTIVE.equals(eff1.getVerdict())
                            && MedConcurrentAudit.VERDICT_EFFECTIVE.equals(eff2.getVerdict())
                            && !eff1.getBizKey().equals(eff2.getBizKey()),
                    "跨池并发应在不同业务键各留一条生效审计");
            check(bizLockMapper.selectCount(new QueryWrapper<MedBizLock>()) == 0L,
                    "业务完成后并发锁行应全部释放");

            // 确定性同池竞争：持锁中的生效请求 xsubmit-hold，并发请求 xsubmit-lose 必须被拒绝
            Long same1 = draftOnly(pondC, "2026-10-10", "xreq-same1");
            Long same2 = draftOnly(pondC, "2026-10-10", "xreq-same2");
            holdLock(pondC, "xsubmit-hold", "申请人甲");
            racePair(
                    () -> postSubmit(same1, "申请人甲", "xsubmit-hold"),
                    () -> postSubmit(same2, "申请人乙", "xsubmit-lose"));
            releaseLock(pondC);
            check(MedRequisition.STATUS_PENDING.equals(requisitionMapper.selectById(same1).getStatus())
                            && MedRequisition.STATUS_DRAFT.equals(requisitionMapper.selectById(same2).getStatus()),
                    "同池并发：持锁请求生效，被拒单停留草稿");
            JsonNode loser = json(lastResponses.get("xsubmit-lose"));
            check(!loser.path("success").asBoolean(true)
                            && loser.path("message").asText().contains("xsubmit-hold")
                            && loser.path("message").asText().contains("申请人甲"),
                    "被拒响应必须指认生效请求与操作者：" + loser.path("message").asText());
            MedConcurrentAudit rejected = auditOf("xsubmit-lose");
            check(rejected != null
                            && MedConcurrentAudit.VERDICT_REJECTED.equals(rejected.getVerdict())
                            && "xsubmit-hold".equals(rejected.getEffectiveRequestId())
                            && "申请人甲".equals(rejected.getEffectiveOperator()),
                    "被拒方必须留 CONCURRENT_REJECTED 审计指认生效请求");

            // 护栏级真实双线程竞争：唯一键裁决恰一生效一被拒
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger effectiveCount = new AtomicInteger();
            List<MedConcurrentRejectedException> losers = new ArrayList<>();
            Thread t1 = new Thread(guardRacer(barrier, pondE, "xrace-a", "操作者甲", effectiveCount, losers), "xrace-a");
            Thread t2 = new Thread(guardRacer(barrier, pondE, "xrace-b", "操作者乙", effectiveCount, losers), "xrace-b");
            t1.start();
            t2.start();
            t1.join(15000);
            t2.join(15000);
            check(effectiveCount.get() == 1 && losers.size() == 1,
                    "同池真实并发必须恰一生效一被拒，生效=" + effectiveCount.get() + "，被拒=" + losers.size());
            check(losers.get(0).getMessage().contains("xrace-a")
                            || losers.get(0).getMessage().contains("xrace-b"),
                    "被拒异常必须指认生效请求：" + losers.get(0).getMessage());
        });

        checkpoint("CP5", "撤回与审核并发双向裁决：撤回先生效则审核被拒，审核先生效则撤回被拒，账实一致", () -> {
            // 方向一：撤回持锁先生效，审核被拒，库存不动、无审核结论
            Long withdrawFirst = draftSubmit(pondD, "2026-10-10", "xreq-wf", "xsubmit-wf");
            holdLock(pondD, "xwd-hold", "申请人林养");
            racePair(
                    () -> postWithdraw(withdrawFirst, "申请人林养", "xwd-hold"),
                    () -> postApprove(withdrawFirst, "陈兽医", "同意", "xapv-lose1"));
            releaseLock(pondD);
            check(MedRequisition.STATUS_WITHDRAWN.equals(requisitionMapper.selectById(withdrawFirst).getStatus()),
                    "撤回先生效：领用单应为已撤回");
            JsonNode approveLoser = json(lastResponses.get("xapv-lose1"));
            check(!approveLoser.path("success").asBoolean(true)
                            && approveLoser.path("message").asText().contains("xwd-hold"),
                    "并发中的审核应被拒绝并指认生效的撤回请求：" + approveLoser.path("message").asText());
            check(availableOf(batchRace).compareTo(new BigDecimal("10000")) == 0,
                    "撤回先生效时审核不得扣减库存，实际可用=" + availableOf(batchRace));
            check(evidenceCount(MedChangeEvidence.ACTION_REQ_APPROVE, withdrawFirst) == 0,
                    "不得写入审核通过结论");

            // 方向二：审核持锁先生效，撤回被拒，库存已扣且审核历史不可再撤
            Long approveFirst = draftSubmit(pondD, "2026-10-10", "xreq-af", "xsubmit-af");
            holdLock(pondD, "xapv-hold", "陈兽医");
            racePair(
                    () -> postApprove(approveFirst, "陈兽医", "同意领用", "xapv-hold"),
                    () -> postWithdraw(approveFirst, "申请人林养", "xwd-lose2"));
            releaseLock(pondD);
            check(MedRequisition.STATUS_APPROVED.equals(requisitionMapper.selectById(approveFirst).getStatus()),
                    "审核先生效：领用单应为已审核");
            check(availableOf(batchRace).compareTo(new BigDecimal("8400")) == 0,
                    "四号池800m³×2=1600应已扣减，实际可用=" + availableOf(batchRace));
            JsonNode withdrawLoser = json(lastResponses.get("xwd-lose2"));
            check(!withdrawLoser.path("success").asBoolean(true)
                            && withdrawLoser.path("message").asText().contains("xapv-hold"),
                    "并发中的撤回应被拒绝并指认生效的审核请求：" + withdrawLoser.path("message").asText());
            JsonNode withdrawAfter = json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + approveFirst + "/withdraw",
                    actionBody("xwd-after")));
            check(!withdrawAfter.path("success").asBoolean(true)
                            && withdrawAfter.path("message").asText().contains("不可撤回"),
                    "审核落定后撤回应被拒绝：" + withdrawAfter.path("message").asText());
            check(availableOf(batchRace).compareTo(new BigDecimal("8400")) == 0
                            && evidenceCount(MedChangeEvidence.ACTION_REQ_APPROVE, approveFirst) == 1,
                    "已审核结论与库存扣减不得被后续操作静默重算");
        });

        checkpoint("CP6", "撤回再重提：领用编号带V2、批次关联版本递增、提交时间新写入，原单只读保留", () -> {
            MedRequisition origin = requisitionMapper.selectById(reqNewId);
            check(MedRequisition.STATUS_PENDING.equals(origin.getStatus()), "前置：另一批次单在待审核");

            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + reqNewId + "/withdraw",
                    actionBody("xwd-new"))), "撤回二号池另一批次单");
            origin = requisitionMapper.selectById(reqNewId);
            check(lockMapper.selectCount(new QueryWrapper<MedWithdrawalLock>().eq("pond_id", pondB)) == 0L,
                    "二号池在途单全部撤回后休药锁应释放");

            JsonNode renewedNode = json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + reqNewId + "/resubmit",
                    actionBody("xrs-new")));
            assertSuccess(renewedNode, "撤回重提");
            Long renewedId = renewedNode.path("data").path("id").asLong();
            check(!renewedId.equals(reqNewId), "重提应生成新领用单而非改原单");
            MedRequisition renewed = requisitionMapper.selectById(renewedId);
            check((origin.getReqNo() + "V2").equals(renewed.getReqNo()),
                    "新单编号应带V2且基于原编号，原=" + origin.getReqNo() + "，新=" + renewed.getReqNo());
            check(renewed.getLinkVersion() == 2 && origin.getLinkVersion() == 1,
                    "批次关联版本应原单V1、新单V2");
            check(reqNewId.equals(renewed.getResubmittedOfId()), "新单应可反查原单id");
            check(renewed.getSubmitTime() != null
                            && renewed.getSubmitTime().isAfter(origin.getWithdrawTime()),
                    "新单提交时间应为重提时刻且晚于撤回时间：原撤回="
                            + origin.getWithdrawTime() + "，新提交=" + renewed.getSubmitTime());

            MedRequisitionItem v2Item = soleItem(renewedId);
            check(v2Item.getBatchVersion() == 2
                            && "AA-2026-NEW".equals(v2Item.getBatchNo())
                            && pondB.equals(v2Item.getPondId())
                            && "2026-10-12".equals(v2Item.getUseDateSnapshot().toString())
                            && new BigDecimal("2").compareTo(v2Item.getDosagePerM3()) == 0
                            && v2Item.getWithdrawalDays() == 20
                            && "2026-11-01".equals(v2Item.getWithdrawalEndDate().toString())
                            && new BigDecimal("4000.00").compareTo(v2Item.getEstimatedQty()) == 0,
                    "重提明细应携带V2批次关联版本且沿用同一批号/剂量/休药期/用量，实际=" + describe(v2Item));
            MedWithdrawalLock lockB = lockOf(pondB);
            check(lockB != null && "2026-11-01".equals(lockB.getLockedUntil().toString())
                            && renewedId.equals(lockB.getSourceReqId())
                            && "AA-2026-NEW".equals(lockB.getSourceBatchNo()),
                    "重提后二号池锁应来自V2新单与另一批次，实际=" + summarize(lockB));

            MedRequisition originAgain = requisitionMapper.selectById(reqNewId);
            check(MedRequisition.STATUS_WITHDRAWN.equals(originAgain.getStatus())
                            && originAgain.getLinkVersion() == 1
                            && originAgain.getReqNo().equals(origin.getReqNo()),
                    "原撤回单必须只读保留（状态/编号/版本不变）");
            check(evidenceCount(MedChangeEvidence.ACTION_REQ_RESUBMIT, reqNewId) == 1
                            && evidenceCount(MedChangeEvidence.ACTION_REQ_WITHDRAW, reqNewId) == 1,
                    "原单应保留撤回与重提各一条证据");
            List<MedChangeEvidence> newChain = evidenceOf(renewedId);
            List<String> newActions = new ArrayList<>();
            newChain.forEach(e -> newActions.add(e.getAction()));
            check(newActions.containsAll(Arrays.asList(
                            MedChangeEvidence.ACTION_REQ_CREATE, MedChangeEvidence.ACTION_REQ_SUBMIT)),
                    "V2新单应有独立创建/提交证据链，实际=" + newActions);

            JsonNode replay = json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + reqNewId + "/resubmit",
                    actionBody("xrs-new")));
            assertSuccess(replay, "同requestId重放重提");
            check(replay.path("data").path("id").asLong() == renewedId,
                    "重放必须返回同一V2单，不得再生成新版本");
            JsonNode again = json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + reqNewId + "/resubmit",
                    actionBody("xrs-new-again")));
            check(!again.path("success").asBoolean(true)
                            && again.path("message").asText().contains("已重提"),
                    "换requestId对原单二次重提应被拒绝：" + again.path("message").asText());
        });

        checkpoint("CP7", "已审核/已驳回是历史事实：不可撤回、不可重提，锁定与库存不静默重算", () -> {
            // 一号池在途只有早批次单：审核后锁来源必为该单，便于勾稽
            assertSuccess(json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + reqOldId + "/approve",
                    reviewBody("陈兽医", "同意按方领用", "xapv-fix"))), "审核通过一号池早批次单");
            check(availableOf(batchOld).compareTo(new BigDecimal("4000")) == 0,
                    "一号池3000m³×2=6000应已扣减，实际可用=" + availableOf(batchOld));
            MedWithdrawalLock lockA = lockOf(pondA);
            check(lockA != null && "2026-10-30".equals(lockA.getLockedUntil().toString())
                            && reqOldId.equals(lockA.getSourceReqId())
                            && "AA-2025-OLD".equals(lockA.getSourceBatchNo()),
                    "一号池锁应仍来自已审核早批次单10-30，实际=" + summarize(lockA));

            JsonNode withdraw = json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + reqOldId + "/withdraw",
                    actionBody("xwd-apv")));
            check(!withdraw.path("success").asBoolean(true)
                            && withdraw.path("message").asText().contains("不可撤回"),
                    "已审核单不可撤回：" + withdraw.path("message").asText());
            JsonNode resubmit = json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + reqOldId + "/resubmit",
                    actionBody("xrs-apv")));
            check(!resubmit.path("success").asBoolean(true)
                            && resubmit.path("message").asText().contains("历史事实"),
                    "已审核单不可重提：" + resubmit.path("message").asText());

            Long rejectedReq = draftSubmit(pondC, "2026-10-10", "xreq-rej", "xsubmit-rej");
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/med/requisitions/" + rejectedReq + "/reject",
                    reviewBody("陈兽医", "指征不符驳回", "xrej-fix"))), "审核驳回");
            JsonNode resubmitRejected = json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + rejectedReq + "/resubmit",
                    actionBody("xrs-rej")));
            check(!resubmitRejected.path("success").asBoolean(true)
                            && resubmitRejected.path("message").asText().contains("历史事实"),
                    "已驳回单不可重提：" + resubmitRejected.path("message").asText());

            check(MedRequisition.STATUS_APPROVED.equals(requisitionMapper.selectById(reqOldId).getStatus())
                            && MedRequisition.STATUS_REJECTED.equals(requisitionMapper.selectById(rejectedReq).getStatus())
                            && availableOf(batchOld).compareTo(new BigDecimal("4000")) == 0
                            && evidenceCount(MedChangeEvidence.ACTION_REQ_APPROVE, reqOldId) == 1,
                    "并发/重提尝试后历史审核结论与库存必须原样保留");
            MedWithdrawalLock lockAAfter = lockOf(pondA);
            check(lockAAfter != null
                            && "2026-10-30".equals(lockAAfter.getLockedUntil().toString())
                            && reqOldId.equals(lockAAfter.getSourceReqId()),
                    "锁定来源不得静默漂移，实际=" + summarize(lockAAfter));
        });

        checkpoint("CP8", "行快照池塘被改串（跨池批次上下文串用）时提交直接拒绝，不出错误锁定、不提前出池日", () -> {
            JsonNode corruptReq = json(call(HttpMethod.POST, "/api/aqua/med/requisitions",
                    reqBody(pondE, "2026-10-10", "xreq-corrupt", item(drugA, batchE, null))));
            assertSuccess(corruptReq, "五号池建草稿");
            Long corruptId = corruptReq.path("data").path("id").asLong();
            MedRequisitionItem good = soleItem(corruptId);
            // 模拟上下文串用：明细行的批次关联池塘被改成一号池
            good.setPondId(pondA);
            itemMapper.updateById(good);

            JsonNode denied = json(call(HttpMethod.POST,
                    "/api/aqua/med/requisitions/" + corruptId + "/submit",
                    actionBody("xsubmit-corrupt")));
            check(!denied.path("success").asBoolean(true),
                    "行快照池塘与头单不一致必须拒绝提交：" + denied.path("message").asText());
            check(MedRequisition.STATUS_DRAFT.equals(requisitionMapper.selectById(corruptId).getStatus()),
                    "串用单必须停留草稿");
            check(soleItem(corruptId).getWithdrawalEndDate() == null,
                    "不得写入任何休药结束日");
            check(lockMapper.selectCount(new QueryWrapper<MedWithdrawalLock>().eq("pond_id", pondE)) == 0L,
                    "串用单不得产生池塘休药锁定");
            check(bizLockMapper.selectCount(new QueryWrapper<MedBizLock>()) == 0L,
                    "异常后不得残留并发锁");
        });

        report.add("验收结论：全部检查点通过——同药跨池批次行级快照隔离（早批次休药期不串另一批次、出池日不被提前）、"
                + "多药叠加取最晚且撤回后回到剩余最晚来源、同池并发提交/审核/撤回裁决留双向审计而跨池互不阻塞、"
                + "撤回重提编号/版本/时间均可辨且原单只读、历史审核结果不静默重算、串用上下文直接拒绝");
        printReport();
    }

    // ------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------

    private Long createPond(String code, String name, String waterAreaM2, String waterDepthM) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("pondCode", code);
        body.put("pondName", name);
        body.put("seaArea", "东山湾海区");
        body.put("waterDepthM", waterDepthM);
        body.put("waterAreaM2", waterAreaM2);
        body.put("salinityPpt", "28.5");
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/ponds", body));
        assertSuccess(resp, "建池[" + code + "]");
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> drugBody(String code, String name, String dosage, String withdrawalDays,
                                         String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("drugCode", code);
        body.put("drugName", name);
        body.put("spec", "100g/袋");
        body.put("unit", "g");
        body.put("dosagePerM3", dosage);
        body.put("withdrawalDays", withdrawalDays);
        body.put("operator", "药政员");
        body.put("requestId", requestId);
        return body;
    }

    private Long stockInBatch(String batchNo, Long drugId, String quantity, String requestId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("batchNo", batchNo);
        body.put("drugId", drugId);
        body.put("supplier", "闽东兽药经营部");
        body.put("quantityIn", quantity);
        body.put("productionDate", "2026-06-01");
        body.put("expiryDate", "2027-06-01");
        body.put("operator", "仓管员王库");
        body.put("requestId", requestId);
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/med/batches", body));
        assertSuccess(resp, "批号入库[" + batchNo + "]");
        return resp.path("data").path("id").asLong();
    }

    private Map<String, Object> item(Long drugId, Long medBatchId, String dosagePerM3) {
        Map<String, Object> item = new HashMap<>();
        item.put("drugId", drugId);
        item.put("medBatchId", medBatchId);
        if (dosagePerM3 != null) {
            item.put("dosagePerM3", dosagePerM3);
        }
        return item;
    }

    private Map<String, Object> reqBody(Long pondId, String useDate, String requestId,
                                        Map<String, Object>... items) {
        Map<String, Object> body = new HashMap<>();
        body.put("pondId", pondId);
        body.put("diseaseDesc", "细菌性感染防治");
        body.put("useDate", useDate);
        body.put("applicant", "申请人林养");
        body.put("requestId", requestId);
        body.put("items", Arrays.asList(items));
        return body;
    }

    private Map<String, Object> actionBody(String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("operator", "申请人林养");
        body.put("requestId", requestId);
        return body;
    }

    private Map<String, Object> reviewBody(String approvedBy, String remark, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("approvedBy", approvedBy);
        body.put("approveRemark", remark);
        body.put("requestId", requestId);
        return body;
    }

    private Long draftOnly(Long pondId, String useDate, String requestId) throws Exception {
        JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/med/requisitions",
                reqBody(pondId, useDate, requestId, item(drugA, batchRace, null))));
        assertSuccess(resp, "建草稿[" + requestId + "]");
        return resp.path("data").path("id").asLong();
    }

    private Long draftSubmit(Long pondId, String useDate, String reqRequestId, String submitRequestId)
            throws Exception {
        Long id = draftOnly(pondId, useDate, reqRequestId);
        assertSuccess(json(postSubmit(id, "申请人林养", submitRequestId)), "提交[" + reqRequestId + "]");
        return id;
    }

    private ResponseEntity<String> postSubmit(Long id, String operator, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("operator", operator);
        body.put("requestId", requestId);
        return callAndStore(requestId, HttpMethod.POST,
                "/api/aqua/med/requisitions/" + id + "/submit", body);
    }

    private ResponseEntity<String> postWithdraw(Long id, String operator, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("operator", operator);
        body.put("requestId", requestId);
        return callAndStore(requestId, HttpMethod.POST,
                "/api/aqua/med/requisitions/" + id + "/withdraw", body);
    }

    private ResponseEntity<String> postApprove(Long id, String approvedBy, String remark, String requestId) {
        return callAndStore(requestId, HttpMethod.POST,
                "/api/aqua/med/requisitions/" + id + "/approve", reviewBody(approvedBy, remark, requestId));
    }

    /** 两个请求在屏障处同时放行，各存响应。 */
    private void racePair(HttpCall a, HttpCall b) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                a.run();
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        }, "race-a");
        Thread t2 = new Thread(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                b.run();
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        }, "race-b");
        t1.start();
        t2.start();
        t1.join(15000);
        t2.join(15000);
        if (error.get() != null) {
            throw new IllegalStateException("并发请求线程异常", error.get());
        }
    }

    private interface HttpCall {
        ResponseEntity<String> run() throws Exception;
    }

    private void holdLock(Long pondId, String requestId, String operator) {
        MedBizLock lock = new MedBizLock();
        lock.setLockKey(MedBizLock.keyOfPond(pondId));
        lock.setRequestId(requestId);
        lock.setOperator(operator);
        lock.setLockedAt(LocalDateTime.now());
        bizLockMapper.insert(lock);
    }

    private void releaseLock(Long pondId) {
        bizLockMapper.delete(new QueryWrapper<MedBizLock>().eq("lock_key", MedBizLock.keyOfPond(pondId)));
    }

    private Runnable guardRacer(CyclicBarrier barrier, Long pondId, String requestId, String operator,
                                AtomicInteger effectiveCount, List<MedConcurrentRejectedException> losers) {
        return () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                MedConcurrencyGuard.LeasedLock lease;
                try {
                    lease = concurrencyGuard.tryAcquire(pondId, "RACE_PROBE", requestId, operator);
                } catch (MedConcurrentRejectedException ex) {
                    synchronized (losers) {
                        losers.add(ex);
                    }
                    return;
                }
                effectiveCount.incrementAndGet();
                concurrencyGuard.recordEffective(lease, "RACE_PROBE");
                Thread.sleep(1500);
                concurrencyGuard.release(lease);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        };
    }

    private MedRequisitionItem soleItem(Long requisitionId) {
        List<MedRequisitionItem> items = itemMapper.selectList(new QueryWrapper<MedRequisitionItem>()
                .eq("requisition_id", requisitionId).orderByAsc("id"));
        check(items.size() == 1, "期望单行明细，实际=" + items.size());
        return items.get(0);
    }

    private MedWithdrawalLock lockOf(Long pondId) {
        return lockMapper.selectOne(new QueryWrapper<MedWithdrawalLock>()
                .eq("pond_id", pondId).last("limit 1"));
    }

    private MedConcurrentAudit auditOf(String requestId) {
        return concurrentAuditMapper.selectOne(new QueryWrapper<MedConcurrentAudit>()
                .eq("request_id", requestId).last("limit 1"));
    }

    private List<MedChangeEvidence> evidenceOf(Long requisitionId) {
        return evidenceMapper.selectList(new QueryWrapper<MedChangeEvidence>()
                .eq("entity_type", MedChangeEvidence.ENTITY_REQUISITION)
                .eq("entity_id", requisitionId).orderByAsc("id"));
    }

    private long evidenceCount(String action, Long entityId) {
        Long c = evidenceMapper.selectCount(new QueryWrapper<MedChangeEvidence>()
                .eq("entity_type", MedChangeEvidence.ENTITY_REQUISITION)
                .eq("action", action)
                .eq("entity_id", entityId));
        return c == null ? 0L : c;
    }

    private BigDecimal availableOf(Long medBatchId) {
        return medBatchMapper.selectById(medBatchId).availableQuantity();
    }

    private String describe(MedRequisitionItem item) {
        return "[批号=" + item.getBatchNo() + "，池=" + item.getPondId()
                + "，用药日快照=" + item.getUseDateSnapshot() + "，版本=V" + item.getBatchVersion()
                + "，休药结束=" + item.getWithdrawalEndDate() + "]";
    }

    private String summarize(MedWithdrawalLock lock) {
        if (lock == null) {
            return "无锁";
        }
        return "[lockedUntil=" + lock.getLockedUntil() + "，来源单=" + lock.getSourceReqNo()
                + "，批号=" + lock.getSourceBatchNo() + "，药品=" + lock.getSourceDrugName() + "]";
    }

    private ResponseEntity<String> call(HttpMethod method, String url, Object body) {
        return callAndStore(null, method, url, body);
    }

    private ResponseEntity<String> callAndStore(String key, HttpMethod method, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sessionCookie != null) {
            headers.add(HttpHeaders.COOKIE, sessionCookie);
        }
        ResponseEntity<String> response =
                rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
        if (key != null) {
            synchronized (lastResponses) {
                lastResponses.put(key, response);
            }
        }
        return response;
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertSuccess(JsonNode node, String what) {
        if (!node.path("success").asBoolean(false)) {
            throw new IllegalStateException(what + "接口返回失败：" + node.path("message").asText());
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private void checkpoint(String id, String name, CheckpointBody body) {
        try {
            body.run();
            String line = "通过 " + id + " " + name;
            report.add(line);
            System.out.println("[验收] " + line);
        } catch (Throwable t) {
            String stop = "停点 " + id + "「" + name + "」失败，原因：" + t.getMessage();
            report.add(stop);
            report.add("验收结论：未通过，后续检查点未执行");
            printReport();
            fail("验收" + stop);
        }
    }

    private void printReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== 用药批次关联修复验收报告 ==========\n");
        for (String line : report) {
            sb.append(line).append('\n');
        }
        sb.append("============================================\n");
        System.out.println(sb);
    }

    private interface CheckpointBody {
        void run() throws Exception;
    }
}

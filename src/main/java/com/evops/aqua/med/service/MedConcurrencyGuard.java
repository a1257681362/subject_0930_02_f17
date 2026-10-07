package com.evops.aqua.med.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.med.entity.MedBizLock;
import com.evops.aqua.med.entity.MedConcurrentAudit;
import com.evops.aqua.med.mapper.MedBizLockMapper;
import com.evops.aqua.med.mapper.MedConcurrentAuditMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用药领用同池并发裁决护栏。
 *
 * 裁决口径（谁生效、谁被拒绝）：
 * 1. 锁键为池塘（MED_POND_LOCK:{pondId}）：同一池塘的提交/驳回/撤回串行化，
 *    先拿到 t_med_biz_lock 唯一键的请求 EFFECTIVE 生效；
 * 2. 同池其他并发请求拿锁失败 → 立即 CONCURRENT_REJECTED 被拒绝，响应说明生效请求号与操作者；
 *    不同池塘锁键不同，跨池并发互不阻塞；
 * 3. 生效与被拒请求都在 t_med_concurrent_audit 留一行；被拒审计独立事务落库，不随业务回滚；
 * 4. 持锁事务结束后锁行删除；同一 requestId 重放按业务幂等返回首次结果（不算并发拒绝）。
 */
@Service
public class MedConcurrencyGuard {

    private final MedBizLockMapper lockMapper;
    private final MedConcurrentAuditMapper auditMapper;
    /** 自代理：拒绝审计必须在独立事务提交，不能随本方法抛异常回滚，故经代理调用 REQUIRES_NEW 方法。 */
    private final MedConcurrencyGuard self;

    public MedConcurrencyGuard(MedBizLockMapper lockMapper,
                               MedConcurrentAuditMapper auditMapper,
                               @Lazy MedConcurrencyGuard self) {
        this.lockMapper = lockMapper;
        this.auditMapper = auditMapper;
        this.self = self;
    }

    /**
     * 尝试取得池塘休药锁定重算执行权。锁行以独立事务立即提交，对所有并发请求立即可见。
     *
     * @return 生效凭据（同 requestId 重入也算生效，由业务层自行保证幂等）
     * @throws MedConcurrentRejectedException 并发被拒，审计已独立事务留痕
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public LeasedLock tryAcquire(Long pondId, String action, String requestId, String operator) {
        String bizKey = MedBizLock.keyOfPond(pondId);
        MedBizLock existing = lockMapper.selectOne(new QueryWrapper<MedBizLock>().eq("lock_key", bizKey));
        if (existing != null) {
            if (existing.getRequestId().equals(requestId)) {
                // 同请求重入：生效凭据回放，不重复审计
                return new LeasedLock(bizKey, requestId, operator, false);
            }
            self.rejectStandalone(bizKey, action, requestId, operator,
                    existing.getRequestId(), existing.getOperator(),
                    "同一池塘用药操作正在处理中：生效请求=" + existing.getRequestId()
                            + "（操作者" + existing.getOperator() + "），本请求=" + requestId
                            + "（操作者" + operator + "）并发被拒");
            throw new MedConcurrentRejectedException("同一池塘用药操作已在处理中：请求" + requestId
                    + "（操作者" + operator + "）被拒绝，请求" + existing.getRequestId()
                    + "（操作者" + existing.getOperator() + "）先生效");
        }
        MedBizLock lock = new MedBizLock();
        lock.setLockKey(bizKey);
        lock.setRequestId(requestId);
        lock.setOperator(operator);
        lock.setLockedAt(LocalDateTime.now());
        try {
            lockMapper.insert(lock);
        } catch (DuplicateKeyException dup) {
            // 并发窗口极小：唯一约束兜底，再查一次拿到生效者信息
            MedBizLock winner = lockMapper.selectOne(new QueryWrapper<MedBizLock>().eq("lock_key", bizKey));
            String winnerRequest = winner == null ? requestId : winner.getRequestId();
            String winnerOperator = winner == null ? operator : winner.getOperator();
            self.rejectStandalone(bizKey, action, requestId, operator, winnerRequest, winnerOperator,
                    "同一池塘用药操作并发竞争唯一执行权失败：生效请求=" + winnerRequest
                            + "，本请求=" + requestId + "被拒");
            throw new MedConcurrentRejectedException("同一池塘用药操作并发冲突：请求" + requestId
                    + "（操作者" + operator + "）被拒绝，请求" + winnerRequest
                    + "（操作者" + winnerOperator + "）先生效");
        }
        return new LeasedLock(bizKey, requestId, operator, true);
    }

    /**
     * 生效请求落审计行（业务事务内，随业务一起提交——业务失败回滚则该请求未生效）。
     * 同一 requestId 此前若留有并发被拒行（客户端被拒后用同号重试成功），不再插入——
     * 审计保留首次裁决结论，靠业务证据链表达重试后的生效结果。
     */
    public void recordEffective(LeasedLock lease, String action) {
        if (!lease.isFresh()) {
            return;
        }
        Long existed = auditMapper.selectCount(new QueryWrapper<MedConcurrentAudit>()
                .eq("request_id", lease.getRequestId()));
        if (existed != null && existed > 0) {
            return;
        }
        MedConcurrentAudit audit = new MedConcurrentAudit();
        audit.setBizKey(lease.getBizKey());
        audit.setAction(action);
        audit.setVerdict(MedConcurrentAudit.VERDICT_EFFECTIVE);
        audit.setRequestId(lease.getRequestId());
        audit.setOperator(lease.getOperator());
        audit.setEffectiveRequestId(lease.getRequestId());
        audit.setEffectiveOperator(lease.getOperator());
        auditMapper.insert(audit);
    }

    /**
     * 被拒审计独立事务落库：即使外层业务回滚，"谁被谁拒绝"的事实也必须留下。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void rejectStandalone(String bizKey, String action, String requestId, String operator,
                                 String effectiveRequestId, String effectiveOperator, String reason) {
        MedConcurrentAudit existing = auditMapper.selectOne(
                new QueryWrapper<MedConcurrentAudit>().eq("request_id", requestId));
        if (existing != null) {
            return; // 审计只追加首次结论，同请求重放不覆盖
        }
        MedConcurrentAudit audit = new MedConcurrentAudit();
        audit.setBizKey(bizKey);
        audit.setAction(action);
        audit.setVerdict(MedConcurrentAudit.VERDICT_REJECTED);
        audit.setRequestId(requestId);
        audit.setOperator(operator);
        audit.setEffectiveRequestId(effectiveRequestId);
        audit.setEffectiveOperator(effectiveOperator);
        audit.setRejectReason(reason);
        auditMapper.insert(audit);
    }

    /**
     * 释放池塘执行权：注册到当前业务事务结束后（提交或回滚）以独立事务删除锁行。
     * 业务数据真正落库前锁对其他并发请求始终可见，无"业务未提交但锁已释放"空窗。
     */
    public void release(LeasedLock lease) {
        if (lease == null || !lease.isFresh()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    deleteLock(lease.getBizKey());
                }
            });
        } else {
            deleteLock(lease.getBizKey());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void deleteLock(String lockKey) {
        lockMapper.delete(new QueryWrapper<MedBizLock>().eq("lock_key", lockKey));
    }

    public List<MedConcurrentAudit> listAudit(String bizKey, String requestId) {
        QueryWrapper<MedConcurrentAudit> wrapper = new QueryWrapper<>();
        wrapper.eq(bizKey != null && !bizKey.trim().isEmpty(), "biz_key", bizKey);
        wrapper.eq(requestId != null && !requestId.trim().isEmpty(), "request_id", requestId);
        wrapper.orderByAsc("id");
        return auditMapper.selectList(wrapper);
    }

    /**
     * 生效凭据。fresh=true 表示本次新拿到执行权（需记生效审计）；false 表示同请求重入。
     */
    public static class LeasedLock {
        private final String bizKey;
        private final String requestId;
        private final String operator;
        private final boolean fresh;

        public LeasedLock(String bizKey, String requestId, String operator, boolean fresh) {
            this.bizKey = bizKey;
            this.requestId = requestId;
            this.operator = operator;
            this.fresh = fresh;
        }

        public String getBizKey() { return bizKey; }
        public String getRequestId() { return requestId; }
        public String getOperator() { return operator; }
        public boolean isFresh() { return fresh; }
    }
}

package com.evops.aqua.med.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 用药业务键并发锁：同一池塘休药锁定重算串行化的凭据。唯一约束 uk(lock_key) 保证同键只有一个生效者。
 * 锁键为 "MED_POND_LOCK:" + pondId——同池提交/撤回互斥，跨池各用各的锁互不阻塞。
 * 持锁事务提交/回滚后行删除；后续请求按 requestId 幂等回放首次结果。
 */
@TableName("t_med_biz_lock")
public class MedBizLock extends BaseEntity {

    public static final String keyPrefix = "MED_POND_LOCK:";

    private String lockKey;
    private String requestId;
    private String operator;
    private LocalDateTime lockedAt;

    public static String keyOfPond(Long pondId) {
        return keyPrefix + pondId;
    }

    public String getLockKey() { return lockKey; }
    public void setLockKey(String lockKey) { this.lockKey = lockKey; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }
}

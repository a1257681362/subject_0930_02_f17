package com.evops.aqua.med.service;

/**
 * 用药领用同池并发被拒绝：消息中必须说明谁的请求生效、谁被拒绝。
 */
public class MedConcurrentRejectedException extends IllegalArgumentException {

    public MedConcurrentRejectedException(String message) {
        super(message);
    }
}

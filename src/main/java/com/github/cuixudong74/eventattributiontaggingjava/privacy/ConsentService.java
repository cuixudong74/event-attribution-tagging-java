package com.github.cuixudong74.eventattributiontaggingjava.privacy;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 隐私授权：
 *  - 所有到达事件（含未授权）都计入总量，用于对账；
 *  - 未授权事件仅计数，不进入归因管线；
 *  - 用户级明细读取必须持有效令牌，否则拒绝。
 */
@Service
public class ConsentService {
    public static final String ADMIN_TOKEN = "admin-read-token";

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong consented = new AtomicLong();

    /** 未授权也计入总量（对账口径） */
    public void recordTotal(boolean isConsented) {
        total.incrementAndGet();
        if (isConsented) {
            consented.incrementAndGet();
        }
    }

    public long totalEvents() { return total.get(); }
    public long consentedEvents() { return consented.get(); }
    public long nonConsentedEvents() { return total.get() - consented.get(); }

    /** 越权读取用户级明细时抛 SecurityException */
    public void requireUserDetailAccess(String token) {
        if (!ADMIN_TOKEN.equals(token)) {
            throw new SecurityException("拒绝访问：读取用户级明细需要有效授权令牌");
        }
    }
}

package com.github.cuixudong74.eventattributiontaggingjava.privacy;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隐私授权服务：仅获授权用户的事件可进入归因；未授权事件仍计入总量对账。
 */
@Component
public class ConsentService {

    private final Set<String> consentedUsers = ConcurrentHashMap.newKeySet();

    public ConsentService() {
    }

    public void grant(String userId) {
        if (userId != null) {
            consentedUsers.add(userId);
        }
    }

    public void revoke(String userId) {
        if (userId != null) {
            consentedUsers.remove(userId);
        }
    }

    public boolean isConsented(String userId) {
        return userId != null && consentedUsers.contains(userId);
    }
}

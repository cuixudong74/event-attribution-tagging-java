package com.github.cuixudong74.eventattributiontaggingjava.privacy;

import org.springframework.stereotype.Component;

/**
 * 访问鉴权：读取用户级明细必须获得授权，越权读取被拒绝。
 */
@Component
public class AuthorizationService {

    /** 管理员角色可读取全部用户明细 */
    public static final String ADMIN = "admin";

    public AuthorizationService() {
    }

    /**
     * @param actor        访问发起方（admin 可读取全部；用户仅可读取本人）
     * @param targetUserId 被读取的用户明细归属
     * @throws UnauthorizedAccessException 越权时抛出
     */
    public void authorizeUserDetail(String actor, String targetUserId) {
        if (actor == null || actor.isBlank()) {
            throw new UnauthorizedAccessException("读取用户级明细需要身份凭证");
        }
        if (ADMIN.equals(actor) || actor.equals(targetUserId)) {
            return;
        }
        throw new UnauthorizedAccessException(
                "用户 " + actor + " 无权读取用户 " + targetUserId + " 的归因明细");
    }
}

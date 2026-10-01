package com.github.cuixudong74.eventattributiontaggingjava.identity;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 身份图谱：把 userId / deviceId 视为身份线索，用并查集归并到同一身份键。
 * 规则：
 *  - userId 与 deviceId 均为有效线索；任一出现即参与归并；
 *  - 同一用户跨设备（同 userId 不同 deviceId）或跨渠道（渠道不参与身份）自然归并；
 *  - 两者都缺失时不静默丢弃，返回 NO_IDENTITY:&lt;eventId&gt; 形式的独立键。
 * 身份键为归并分量的规范名（字典序最小线索），保证可复现。
 */
@Component
public class IdentityGraph {
    public static final String NO_IDENTITY_PREFIX = "NO_IDENTITY:";

    private final Map<String, String> parent = new HashMap<>();

    /** 归并线索并返回身份键；两者皆空时返回 null（由调用方用 eventId 兜底） */
    public synchronized String resolve(String userId, String deviceId) {
        boolean hasUser = userId != null && !userId.isBlank();
        boolean hasDevice = deviceId != null && !deviceId.isBlank();
        if (!hasUser && !hasDevice) {
            return null;
        }
        String canonical = null;
        if (hasUser) {
            canonical = find("u:" + userId);
        }
        if (hasDevice) {
            String devRoot = find("d:" + deviceId);
            canonical = canonical == null ? devRoot : union(canonical, devRoot);
        }
        return canonical;
    }

    /** 事件级兜底键：无任何身份线索时仍可区分、可追踪 */
    public String anonymousKey(String eventId) {
        return NO_IDENTITY_PREFIX + eventId;
    }

    private String find(String clue) {
        String p = parent.get(clue);
        if (p == null) {
            parent.put(clue, clue);
            return clue;
        }
        if (p.equals(clue)) {
            return clue;
        }
        String root = find(p);
        parent.put(clue, root); // 路径压缩
        return root;
    }

    /** 归并两个根：优先 userId 线索（登录态更稳定），否则取字典序较小者，保证与归并顺序无关 */
    private String union(String rootA, String rootB) {
        if (rootA.equals(rootB)) {
            return rootA;
        }
        String winner;
        if (rootA.startsWith("u:") != rootB.startsWith("u:")) {
            winner = rootA.startsWith("u:") ? rootA : rootB;
        } else {
            winner = rootA.compareTo(rootB) <= 0 ? rootA : rootB;
        }
        String loser = winner.equals(rootA) ? rootB : rootA;
        parent.put(loser, winner);
        return winner;
    }
}

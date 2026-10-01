package com.github.cuixudong74.eventattributiontaggingjava.identity;

import java.util.HashMap;
import java.util.Map;

/**
 * 身份图：以设备/账号标识为节点，通过并查集把同一用户跨设备、跨渠道的标识关联起来。
 * 只有同时携带 userId 与 deviceId 的事件才会建立两者的关联。
 */
public class IdentityGraph {

    private static final String USER_PREFIX = "u:";
    private static final String DEVICE_PREFIX = "d:";

    private final Map<String, String> parent = new HashMap<>();

    public IdentityGraph() {
    }

    /**
     * 建立 userId 与 deviceId 的关联（同一用户在某设备上登录）。
     */
    public void link(String userId, String deviceId) {
        if (userId == null || deviceId == null) {
            return;
        }
        union(USER_PREFIX + userId, DEVICE_PREFIX + deviceId);
    }

    /**
     * 解析事件归属的规范用户：优先取 userId，否则沿设备关联查找；都无法确定时返回 null（匿名）。
     */
    public String resolveCanonical(String userId, String deviceId) {
        if (userId != null) {
            String root = find(USER_PREFIX + userId);
            return root.startsWith(USER_PREFIX) ? root.substring(USER_PREFIX.length()) : null;
        }
        if (deviceId != null) {
            String root = find(DEVICE_PREFIX + deviceId);
            return root.startsWith(USER_PREFIX) ? root.substring(USER_PREFIX.length()) : null;
        }
        return null;
    }

    public void clear() {
        parent.clear();
    }

    private void union(String a, String b) {
        String ra = find(a);
        String rb = find(b);
        if (ra.equals(rb)) {
            return;
        }
        // 优先以用户节点为根；两个用户节点合并时取字典序较小者，保证规范用户确定、不漂移
        if (ra.startsWith(USER_PREFIX) && rb.startsWith(USER_PREFIX)) {
            String root = ra.compareTo(rb) <= 0 ? ra : rb;
            String other = root.equals(ra) ? rb : ra;
            parent.put(other, root);
        } else if (ra.startsWith(USER_PREFIX)) {
            parent.put(rb, ra);
        } else if (rb.startsWith(USER_PREFIX)) {
            parent.put(ra, rb);
        } else {
            parent.put(rb, ra);
        }
    }

    private String find(String x) {
        parent.putIfAbsent(x, x);
        String root = x;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        String cur = x;
        while (!cur.equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }
}

package com.github.cuixudong74.eventattributiontaggingjava.tagging;

import com.github.cuixudong74.eventattributiontaggingjava.model.TagEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 标签服务：按 (identityKey, channel) 维护标签。
 * 覆盖规则（明确且确定）：
 *  - priority 大者覆盖小者；
 *  - 同 priority 时写入序号大者（后写）覆盖；
 *  - 低优先级或同优先级旧写入到达时被拒绝，不产生矛盾状态。
 * 重复写入（同 key/value/priority）幂等；并发更新在锁内比较-交换，不留中间态。
 */
@Service
public class TagService {
    private final Map<String, Map<String, TagEntry>> tags = new ConcurrentHashMap<>();
    private final AtomicLong writeSeq = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();

    /** 写入标签；返回 true 表示生效，false 表示被更高/同级更新的值压制 */
    public boolean putTag(String identityKey, String channel, String key, String value, int priority) {
        lock.lock();
        try {
            long seq = writeSeq.incrementAndGet();
            Map<String, TagEntry> scope = tags.computeIfAbsent(scopeKey(identityKey, channel),
                    k -> new HashMap<>());
            TagEntry existing = scope.get(key);
            if (existing != null) {
                if (priority < existing.priority()) {
                    return false; // 低优先级不得覆盖
                }
                if (priority == existing.priority() && existing.writeSeq() >= seq) {
                    return false; // 理论不可达（seq 单调），防御性保留
                }
                if (priority == existing.priority() && existing.value().equals(value)) {
                    return true; // 幂等重复写入，状态不变
                }
            }
            scope.put(key, new TagEntry(key, value, priority, seq, Instant.now()));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 一致性快照读取（拷贝，不受并发写影响） */
    public Map<String, TagEntry> tagsOf(String identityKey, String channel) {
        lock.lock();
        try {
            Map<String, TagEntry> scope = tags.get(scopeKey(identityKey, channel));
            return scope == null ? Map.of() : Map.copyOf(scope);
        } finally {
            lock.unlock();
        }
    }

    private String scopeKey(String identityKey, String channel) {
        return identityKey + "|" + channel;
    }
}

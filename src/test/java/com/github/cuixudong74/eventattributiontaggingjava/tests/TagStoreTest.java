package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.tag.Tag;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagKey;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签优先级与覆盖规则测试：重复写入与并发更新不产生矛盾标签。
 */
class TagStoreTest {

    private Tag tag(String key, String value, int priority, String source, long updatedAt) {
        return new Tag(key, value, priority, source, updatedAt);
    }

    @Test
    void higherPriorityOverridesLowerPriority() {
        TagStore store = new TagStore();
        TagKey key = new TagKey("u1", "web");
        store.put(key, tag("level", "low", 1, "src-a", 1_000L));
        store.put(key, tag("level", "high", 2, "src-b", 1_001L));

        Optional<Tag> current = store.get(key);
        assertTrue(current.isPresent());
        assertEquals("high", current.get().getValue());

        // 低优先级后续写入不能覆盖
        store.put(key, tag("level", "low2", 1, "src-c", 2_000L));
        assertEquals("high", store.get(key).orElseThrow().getValue());
    }

    @Test
    void samePriorityLaterWriteWins() {
        TagStore store = new TagStore();
        TagKey key = new TagKey("u1", "web");
        store.put(key, tag("seg", "a", 5, "src-a", 1_000L));
        store.put(key, tag("seg", "b", 5, "src-b", 2_000L));

        assertEquals("b", store.get(key).orElseThrow().getValue());
    }

    @Test
    void duplicateWritesAreIdempotentAndConsistent() {
        TagStore store = new TagStore();
        TagKey key = new TagKey("u1", "web");
        Tag t = tag("seg", "x", 3, "src-a", 1_000L);
        store.put(key, t);
        store.put(key, t);
        assertEquals("x", store.get(key).orElseThrow().getValue());
    }

    @Test
    void concurrentUpdatesLeaveNoContradictoryTag() throws Exception {
        TagStore store = new TagStore();
        TagKey key = new TagKey("u1", "web");
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 同优先级、不同时间戳的写入：最终值必为其中之一，不会出现撕裂/矛盾
                        store.put(key, tag("seg", "v" + seed + "-" + i, 5,
                                "src-" + seed, 1_000L + seed * 100L + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        String value = store.get(key).orElseThrow().getValue();
        assertTrue(value.matches("v\\d+-\\d+"));
        // 再次读取结果稳定
        assertEquals(value, store.get(key).orElseThrow().getValue());
    }

    @Test
    void tagsAreMaintainedPerUserAndChannel() {
        TagStore store = new TagStore();
        store.put(new TagKey("u1", "web"), tag("seg", "a", 1, "s", 1L));
        store.put(new TagKey("u1", "mobile"), tag("seg", "b", 1, "s", 2L));
        store.put(new TagKey("u2", "web"), tag("seg", "c", 1, "s", 3L));

        assertEquals("a", store.get(new TagKey("u1", "web")).orElseThrow().getValue());
        assertEquals("b", store.get(new TagKey("u1", "mobile")).orElseThrow().getValue());
        assertEquals("c", store.get(new TagKey("u2", "web")).orElseThrow().getValue());
        assertEquals(2, store.forUser("u1").size());
    }
}

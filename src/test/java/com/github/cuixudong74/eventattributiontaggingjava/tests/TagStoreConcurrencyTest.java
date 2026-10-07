package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.tag.Tag;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagKey;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签并发写入测试：缺少来源标识、写入时间相同、多用户多渠道并发写入时，
 * 任何写入都不失败、不丢失，合并结果唯一、可复现，且生效标签始终是某一次写入的完整结果。
 */
class TagStoreConcurrencyTest {

    /**
     * 高并发下同用户同渠道写入，部分请求缺少来源标识（source=null）且写入时间相同：
     * 所有写入必须成功返回，不得抛异常，最终生效标签必须是其中某一次写入的完整结果。
     */
    @Test
    void concurrentWritesWithMissingSourceAndSameTimestampNeverFail() throws Exception {
        TagStore store = new TagStore();
        TagKey key = new TagKey("u1", "web");
        int threads = 16;
        int perThread = 200;
        long sameTimestamp = 1_000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Tag> allWrites = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 一半请求不带来源标识；全部使用相同优先级与相同写入时间，强制走平局裁决路径
                        String source = (seed % 2 == 0) ? null : "src-" + seed;
                        Tag tag = new Tag("seg", "v" + seed + "-" + i, 5, source, sameTimestamp);
                        allWrites.add(tag);
                        Tag effective = store.put(key, tag);
                        assertNotNull(effective, "写入必须成功返回生效标签");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(); // 任何写入失败（如空指针）都会在此暴露
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Tag winner = store.get(key).orElseThrow();
        // 生效标签必须是某一次写入的完整结果：字段组合与某次真实写入完全一致，不是拼接产物
        assertTrue(allWrites.stream().anyMatch(t ->
                        t.getPriority() == winner.getPriority()
                                && t.getUpdatedAt() == winner.getUpdatedAt()
                                && java.util.Objects.equals(t.getSource(), winner.getSource())
                                && java.util.Objects.equals(t.getKey(), winner.getKey())
                                && java.util.Objects.equals(t.getValue(), winner.getValue())),
                "生效标签不是任何一次写入的完整结果: " + winner.getValue());
        // 再次读取稳定，不回退
        assertEquals(winner.getValue(), store.get(key).orElseThrow().getValue());
    }

    /**
     * 优先级相同且写入时间相同（含来源缺失）时，同一批输入无论以何种顺序合并，
     * 结果必须唯一且可复现。
     */
    @Test
    void sameTimestampMergeIsDeterministicAcrossArrivalOrders() {
        TagKey key = new TagKey("u1", "web");
        List<Tag> batch = List.of(
                new Tag("seg", "a", 5, null, 1_000L),
                new Tag("seg", "b", 5, "src-b", 1_000L),
                new Tag("seg", "c", 5, "src-a", 1_000L),
                new Tag("seg", "d", 5, null, 1_000L),
                new Tag("seg", "e", 5, "src-b", 1_000L));

        Tag expected = mergeInOrder(key, batch);
        Random random = new Random(42L);
        for (int trial = 0; trial < 50; trial++) {
            List<Tag> shuffled = new ArrayList<>(batch);
            Collections.shuffle(shuffled, random);
            Tag actual = mergeInOrder(key, shuffled);
            assertEquals(expected.getValue(), actual.getValue(),
                    "同一批输入不同合并顺序得到不同结论");
            assertEquals(expected.getSource(), actual.getSource());
        }
    }

    private Tag mergeInOrder(TagKey key, List<Tag> tags) {
        TagStore store = new TagStore();
        Tag effective = null;
        for (Tag tag : tags) {
            effective = store.put(key, tag);
        }
        return effective;
    }

    /**
     * 多用户多渠道同时写入：各键互不干扰，每个键的并发合并结果
     * 与同输入顺序合并（参考实现）的确定性结果一致。
     */
    @Test
    void concurrentMultiUserMultiChannelWritesAreIsolatedAndCorrect() throws Exception {
        String[] users = {"u1", "u2", "u3", "u4"};
        String[] channels = {"web", "mobile", "tv"};
        int perKey = 100;

        // 为每个键预生成写入序列（含缺失来源与相同时间戳）
        List<List<Object>> work = new ArrayList<>();
        for (String user : users) {
            for (String channel : channels) {
                TagKey key = new TagKey(user, channel);
                List<Tag> writes = new ArrayList<>();
                for (int i = 0; i < perKey; i++) {
                    String source = (i % 3 == 0) ? null : "src-" + (i % 5);
                    writes.add(new Tag("seg", user + "-" + channel + "-" + i,
                            i % 4, source, 1_000L + (i % 7) * 100L));
                }
                work.add(List.of(key, writes));
            }
        }

        // 参考结果：每个键按同一批输入顺序合并（合并与顺序无关，故即确定性结论）
        TagStore reference = new TagStore();
        for (List<Object> entry : work) {
            TagKey key = (TagKey) entry.get(0);
            @SuppressWarnings("unchecked")
            List<Tag> writes = (List<Tag>) entry.get(1);
            for (Tag tag : writes) {
                reference.put(key, tag);
            }
        }

        // 并发执行：每个键一个写入线程，所有线程同时起跑
        TagStore concurrent = new TagStore();
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (List<Object> entry : work) {
            TagKey key = (TagKey) entry.get(0);
            @SuppressWarnings("unchecked")
            List<Tag> writes = (List<Tag>) entry.get(1);
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (Tag tag : writes) {
                        concurrent.put(key, tag);
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 每个 (用户, 渠道) 的结果都与确定性参考一致，互不干扰
        for (String user : users) {
            for (String channel : channels) {
                TagKey key = new TagKey(user, channel);
                Tag expected = reference.get(key).orElseThrow();
                Tag actual = concurrent.get(key).orElseThrow();
                assertEquals(expected.getValue(), actual.getValue(),
                        "键 " + user + "/" + channel + " 并发合并结果与确定性参考不一致");
                assertEquals(expected.getSource(), actual.getSource());
                assertEquals(expected.getPriority(), actual.getPriority());
            }
        }
        // 用户维度聚合视图也与参考一致
        for (String user : users) {
            assertEquals(reference.forUser(user).size(), concurrent.forUser(user).size());
        }
    }

    /**
     * 并发写入结束后，读取结果必须与最终生效结果一致，且重复读取稳定。
     */
    @Test
    void readsAfterConcurrentWritesReturnEffectiveTag() throws Exception {
        TagStore store = new TagStore();
        TagKey key = new TagKey("u1", "web");
        int threads = 8;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.put(key, new Tag("seg", "v" + seed + "-" + i, 5,
                                (seed % 2 == 0) ? null : "src-" + seed, 1_000L + i));
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
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Tag first = store.get(key).orElseThrow();
        for (int i = 0; i < 100; i++) {
            Tag again = store.get(key).orElseThrow();
            assertEquals(first.getValue(), again.getValue(), "读取到已覆盖的旧值或结果漂移");
            assertEquals(first.getUpdatedAt(), again.getUpdatedAt());
        }
    }
}

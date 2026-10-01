package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.model.TagEntry;
import com.github.cuixudong74.eventattributiontaggingjava.tagging.TagService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TagServiceTest {

    @Test
    void higherPriorityWins() {
        TagService svc = new TagService();
        assertTrue(svc.putTag("u:u1", "chA", "level", "gold", 10));
        assertFalse(svc.putTag("u:u1", "chA", "level", "silver", 5)); // 低优先级被拒
        assertEquals("gold", svc.tagsOf("u:u1", "chA").get("level").value());
    }

    @Test
    void samePriorityLastWriteWins() {
        TagService svc = new TagService();
        svc.putTag("u:u1", "chA", "level", "gold", 10);
        svc.putTag("u:u1", "chA", "level", "platinum", 10);
        assertEquals("platinum", svc.tagsOf("u:u1", "chA").get("level").value());
    }

    @Test
    void duplicateWriteIdempotent() {
        TagService svc = new TagService();
        svc.putTag("u:u1", "chA", "level", "gold", 10);
        TagEntry first = svc.tagsOf("u:u1", "chA").get("level");
        svc.putTag("u:u1", "chA", "level", "gold", 10);
        TagEntry second = svc.tagsOf("u:u1", "chA").get("level");
        assertEquals(first, second); // 状态不变
    }

    @Test
    void scopesAreIsolated() {
        TagService svc = new TagService();
        svc.putTag("u:u1", "chA", "level", "gold", 10);
        assertTrue(svc.tagsOf("u:u1", "chB").isEmpty());
        assertTrue(svc.tagsOf("u:u2", "chA").isEmpty());
    }

    @Test
    void concurrentUpdatesLeaveConsistentState() throws InterruptedException {
        TagService svc = new TagService();
        int threads = 16, writesPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int priority = t; // 每个线程固定优先级，最高优先级线程应最终胜出
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        svc.putTag("u:u1", "chA", "level", "p" + priority, priority);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        Map<String, TagEntry> tags = svc.tagsOf("u:u1", "chA");
        assertEquals(1, tags.size());
        assertEquals("p" + (threads - 1), tags.get("level").value()); // 最高优先级胜出，无矛盾状态
    }
}

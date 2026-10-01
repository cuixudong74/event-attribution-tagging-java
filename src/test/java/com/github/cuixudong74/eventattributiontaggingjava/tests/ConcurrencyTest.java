package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.attribution.AttributionEngine;
import com.github.cuixudong74.eventattributiontaggingjava.config.ConfigManager;
import com.github.cuixudong74.eventattributiontaggingjava.identity.IdentityGraph;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionEvent;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.model.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.ConsentService;
import com.github.cuixudong74.eventattributiontaggingjava.service.IngestionService;
import com.github.cuixudong74.eventattributiontaggingjava.store.EventStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 采集与查询并发：事件不丢失，查询只读到自洽快照 */
class ConcurrencyTest {
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void concurrentIngestAndQuery() throws InterruptedException {
        ConsentService consent = new ConsentService();
        EventStore store = new EventStore();
        IngestionService service = new IngestionService(store, new IdentityGraph(),
                new AttributionEngine(), new ConfigManager(), consent);

        int writers = 8, eventsPerWriter = 100;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger queryRounds = new AtomicInteger();

        // 写线程：每个线程交替写入触点与转化
        for (int w = 0; w < writers; w++) {
            final int wid = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < eventsPerWriter; i++) {
                        String user = "u" + (i % 10);
                        if (i % 2 == 0) {
                            service.ingest(new AttributionEvent("w" + wid + "-t" + i, EventType.CLICK,
                                    user, "d" + user, "ch" + (wid % 3), "c1",
                                    T0.plus(Duration.ofMinutes(i)), 0, true));
                        } else {
                            service.ingest(new AttributionEvent("w" + wid + "-cv" + i, EventType.CONVERSION,
                                    user, "d" + user, null, "c1",
                                    T0.plus(Duration.ofMinutes(i)), 0, true));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 读线程：持续查询快照，验证自洽性（权重和要么为 0 要么为 1）
        for (int r = 0; r < 2; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        Map<String, AttributionResult> snapshot = service.allResults();
                        for (AttributionResult res : snapshot.values()) {
                            double sum = res.getContributions().stream()
                                    .mapToDouble(AttributionResult.Contribution::weight).sum();
                            assertTrue(res.getContributions().isEmpty() || Math.abs(sum - 1.0) < 1e-6,
                                    "快照中权重和必须为 1，实际=" + sum);
                        }
                        queryRounds.incrementAndGet();
                        if (queryRounds.get() > 500) return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        // 所有事件均不丢失：总量 = 去重后存储量
        assertEquals(writers * eventsPerWriter, consent.totalEvents());
        assertEquals(writers * eventsPerWriter, store.totalDistinctEvents());
        assertTrue(queryRounds.get() > 0, "查询确实并发执行过");
    }

    @Test
    void duplicateStormCountsOnce() throws InterruptedException {
        ConsentService consent = new ConsentService();
        EventStore store = new EventStore();
        IngestionService service = new IngestionService(store, new IdentityGraph(),
                new AttributionEngine(), new ConfigManager(), consent);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AttributionEvent same = new AttributionEvent("dup-1", EventType.CLICK, "u1", "d1", "chA",
                "c1", T0, 0, true);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (service.ingest(same)) accepted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, accepted.get(), "同一事件并发重复上报只应被接受一次");
        assertEquals(1, store.totalDistinctEvents());
    }
}

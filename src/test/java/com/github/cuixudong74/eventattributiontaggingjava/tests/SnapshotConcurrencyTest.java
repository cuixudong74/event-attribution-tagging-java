package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.event.Event;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.Reconciliation;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集与查询并发测试：事件不丢失、快照自洽、版本单调、无中间状态。
 */
class SnapshotConcurrencyTest {

    @Test
    void concurrentIngestionAndQueriesRemainConsistent() throws Exception {
        AttributionService service = AttributionTestSupport.service();
        service.grantConsent("u1");

        int threads = 8;
        int perThread = 20;
        int distinctEvents = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t * perThread;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    List<Event> batch = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        int n = (seed + i) % distinctEvents;
                        EventType type = (n % 7 == 6) ? EventType.CONVERSION
                                : (n % 3 == 0 ? EventType.CLICK : EventType.IMPRESSION);
                        batch.add(AttributionTestSupport.event("e" + n, type, "u1", null, "web",
                                1_000L + n * 1_000L));
                    }
                    service.ingest(batch);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // 查询线程：持续读取快照，校验自洽性
        AtomicLong queries = new AtomicLong();
        AtomicLong lastVersion = new AtomicLong(-1);
        Thread reader = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Snapshot snapshot = service.snapshot();
                // 版本单调不减
                assertTrue(snapshot.getVersion() >= lastVersion.get(), "快照版本回退");
                lastVersion.set(snapshot.getVersion());
                // 快照自洽：对账恒等式成立
                Reconciliation reconciliation = snapshot.getReconciliation();
                assertTrue(reconciliation.isBalanced(), "对账不平衡: " + reconciliation);
                // 快照内状态一致：每个事件状态非空；末次触点模型下每个结果恰有一个权重为 1 的触点
                for (EventStatus status : snapshot.getEventStatuses().values()) {
                    assertTrue(status != null);
                }
                for (AttributionResult result : snapshot.getResults().values()) {
                    double weightSum = result.getCredits().stream().mapToDouble(c -> c.getWeight()).sum();
                    assertTrue(weightSum == 0.0 || Math.abs(weightSum - 1.0) < 1e-9,
                            "权重和不自洽: " + weightSum);
                }
                queries.incrementAndGet();
            }
        });
        reader.start();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        reader.interrupt();
        reader.join();
        pool.shutdown();

        // 事件不丢失：接收总数 = 去重事件数 + 重复数
        Reconciliation finalReconciliation = service.reconciliation();
        assertEquals(threads * perThread, finalReconciliation.getTotalReceived());
        assertEquals(distinctEvents,
                finalReconciliation.getLinked() + finalReconciliation.getUnlinked()
                        + finalReconciliation.getUnauthorized());
        assertTrue(finalReconciliation.getDuplicates() > 0);
        assertTrue(queries.get() > 0);
    }
}

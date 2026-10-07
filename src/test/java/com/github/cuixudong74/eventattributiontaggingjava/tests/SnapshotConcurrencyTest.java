package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.event.Event;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionRevision;
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

    @Test
    void concurrentRevisionReadsUseOneSelfConsistentSnapshotVersion() throws Exception {
        AttributionService service = AttributionTestSupport.service();
        service.grantConsent("u1");

        int writers = 4;
        int readers = 6;
        int batchesPerWriter = 40;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int writer = 0; writer < writers; writer++) {
            final int seed = writer * batchesPerWriter;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < batchesPerWriter; i++) {
                        int n = seed + i;
                        service.ingest(List.of(AttributionTestSupport.event(
                                "revision-e" + n, EventType.CONVERSION, "u1", null, "web", 1_000L + n)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        AtomicLong pinnedReads = new AtomicLong();
        for (int reader = 0; reader < readers; reader++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    List<Long> previousRevisionIds = List.of();
                    long previousVersion = 0L;
                    for (int i = 0; i < 100; i++) {
                        Snapshot latest = service.snapshot();
                        if (latest.getVersion() > previousVersion) {
                            List<Long> currentIds = revisionIds(latest.getRevisions());
                            int common = Math.min(previousRevisionIds.size(), currentIds.size());
                            assertEquals(previousRevisionIds.subList(0, common), currentIds.subList(0, common),
                                    "修订历史只能追加，不能回退或丢失");
                            previousRevisionIds = currentIds;
                            previousVersion = latest.getVersion();
                        }

                        if (latest.getVersion() > 0) {
                            long version = latest.getVersion();
                            Snapshot pinnedSnapshot = service.snapshot(version);
                            Reconciliation pinnedReconciliation = service.reconciliation(version);
                            List<AttributionRevision> pinnedRevisions = service.revisions(version);

                            assertEquals(version, pinnedSnapshot.getVersion());
                            pinnedReads.incrementAndGet();
                            assertTrue(sameReconciliation(pinnedSnapshot.getReconciliation(), pinnedReconciliation),
                                    "对账口径必须与快照版本一致");
                            assertEquals(revisionIds(pinnedSnapshot.getRevisions()), revisionIds(pinnedRevisions),
                                    "修订历史必须与快照版本一致");
                            assertStableOrder(pinnedSnapshot.getRevisions());
                            assertEquals(revisionIds(pinnedSnapshot.getRevisions()),
                                    revisionIds(service.revisions(version)),
                                    "同一版本重复读取必须返回相同历史");
                        }
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

        assertTrue(pinnedReads.get() > 0);
        Snapshot finalSnapshot = service.snapshot();
        assertEquals(writers * batchesPerWriter, finalSnapshot.getResults().size());
        assertStableOrder(finalSnapshot.getRevisions());
        assertEquals(finalSnapshot.getRevisions(), service.revisions(finalSnapshot.getVersion()));
    }

    private static List<Long> revisionIds(List<AttributionRevision> revisions) {
        return revisions.stream().map(AttributionRevision::getRevisionId).toList();
    }

    private static void assertStableOrder(List<AttributionRevision> revisions) {
        long previousId = 0L;
        for (AttributionRevision revision : revisions) {
            assertTrue(revision.getRevisionId() > previousId, "修订 ID 必须严格递增");
            previousId = revision.getRevisionId();
            revision.getDiffs().forEach(diff -> assertTrue(diff != null));
        }
    }

    private static boolean sameReconciliation(Reconciliation left, Reconciliation right) {
        return left.getTotalReceived() == right.getTotalReceived()
                && left.getDuplicates() == right.getDuplicates()
                && left.getLinked() == right.getLinked()
                && left.getUnlinked() == right.getUnlinked()
                && left.getUnauthorized() == right.getUnauthorized();
    }
}

package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.event.Event;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionRevision;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 修订历史并发读取测试：采集持续进行时读取修订历史必须来自自洽快照，
 * 不抛异常、不读中间状态、历史单调不回退，且与快照版本、对账口径同属一个版本。
 */
class RevisionHistoryConcurrencyTest {

    /**
     * 采集高峰与大量并发修订历史查询交错：
     * 每次读取都必须完整一致（修订号从 1 连续递增）、
     * 相继读取满足前缀单调（已返回的条目不回退、不丢失）、
     * 且每次读取结果必为某个真实快照版本的修订历史（被前后两次快照读取夹住）。
     */
    @Test
    void concurrentIngestionAndRevisionReadsStayConsistent() throws Exception {
        AttributionService service = AttributionTestSupport.service();
        service.grantConsent("u1");

        int writerThreads = 4;
        int batchesPerWriter = 40;
        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writersDone = new AtomicBoolean(false);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> readerFailures = new CopyOnWriteArrayList<>();

        // 写入线程：持续摄入（含迟到事件，触发 LATE_EVENT 修订）
        for (int w = 0; w < writerThreads; w++) {
            final int seed = w;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int b = 0; b < batchesPerWriter; b++) {
                        List<Event> batch = new ArrayList<>();
                        for (int i = 0; i < 5; i++) {
                            int n = seed * 10_000 + b * 5 + i;
                            EventType type = (n % 11 == 10) ? EventType.CONVERSION
                                    : (n % 3 == 0 ? EventType.CLICK : EventType.IMPRESSION);
                            // 时间戳故意交错回退，制造迟到事件修订
                            long timestamp = 1_000L + (b * 5L + i) * 100L - (b % 3) * 250L;
                            batch.add(AttributionTestSupport.event(
                                    "e" + seed + "-" + n, type, "u1", null, "web", timestamp));
                        }
                        service.ingest(batch);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // 读取线程：采集期间持续读取修订历史并校验一致性
        for (int r = 0; r < 2; r++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    List<AttributionRevision> previous = List.of();
                    while (!writersDone.get() || !Thread.currentThread().isInterrupted()) {
                        Snapshot before = service.snapshot();
                        List<AttributionRevision> revisions = service.revisions();
                        Snapshot after = service.snapshot();

                        // 1) 本次读取内部完整一致：修订号从 1 开始连续递增，无空洞、无错乱
                        for (int i = 0; i < revisions.size(); i++) {
                            if (revisions.get(i).getRevisionId() != i + 1) {
                                throw new AssertionError("修订号不连续: index=" + i
                                        + " id=" + revisions.get(i).getRevisionId());
                            }
                        }
                        // 2) 单调不回退：上一次读取结果是这一次的前缀
                        if (!isPrefix(previous, revisions)) {
                            throw new AssertionError("修订历史回退或丢失条目");
                        }
                        // 3) 版本归属：读取结果介于前后两个快照的修订历史之间，
                        //    即它确实是某个真实快照版本的修订历史
                        if (!isPrefix(before.getRevisions(), revisions)
                                || !isPrefix(revisions, after.getRevisions())) {
                            throw new AssertionError("修订历史与快照版本错位");
                        }
                        previous = revisions;
                        if (writersDone.get()) {
                            break;
                        }
                    }
                } catch (Throwable t) {
                    readerFailures.add(t);
                }
            }));
        }

        start.countDown();
        // 等待写入线程结束
        for (int i = 0; i < writerThreads; i++) {
            futures.get(i).get();
        }
        writersDone.set(true);
        for (int i = writerThreads; i < futures.size(); i++) {
            futures.get(i).get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        if (!readerFailures.isEmpty()) {
            fail("并发读取修订历史失败: " + readerFailures.get(0));
        }

        // 采集停止后：重复读取结果完全相同，且与当前快照的修订历史一致
        List<AttributionRevision> first = service.revisions();
        List<AttributionRevision> second = service.revisions();
        assertEquals(first, second, "无新事件时重复读取结果不同");
        assertEquals(service.snapshot().getRevisions(), first,
                "修订历史与当前快照版本不一致");
        assertTrue(first.size() > 0, "测试未产生任何修订，场景无效");
        // 快照、对账、修订历史同属一个版本：对账恒等式在该快照内成立
        Snapshot snapshot = service.snapshot();
        assertTrue(snapshot.getReconciliation().isBalanced());
        assertEquals(snapshot.getRevisions(), service.revisions());
    }

    /**
     * 无新事件时，修订历史重复读取逐条相等（内容级稳定）。
     */
    @Test
    void revisionHistoryIsStableWithoutNewEvents() {
        AttributionService service = AttributionTestSupport.service();
        service.grantConsent("u1");
        service.ingest(List.of(
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 2_000L)));
        // 迟到事件触发修正
        service.ingest(List.of(
                AttributionTestSupport.event("c1", EventType.CLICK, "u1", null, "web", 1_500L)));

        List<AttributionRevision> first = service.revisions();
        for (int i = 0; i < 20; i++) {
            List<AttributionRevision> again = service.revisions();
            assertEquals(first.size(), again.size());
            for (int j = 0; j < first.size(); j++) {
                assertEquals(first.get(j).getRevisionId(), again.get(j).getRevisionId());
                assertEquals(first.get(j).getReason(), again.get(j).getReason());
                assertEquals(first.get(j).getDiffs().size(), again.get(j).getDiffs().size());
            }
        }
        assertTrue(first.stream().anyMatch(r ->
                AttributionService.REASON_LATE_EVENT.equals(r.getReason())));
    }

    private static boolean isPrefix(List<AttributionRevision> prefix, List<AttributionRevision> full) {
        if (prefix.size() > full.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (prefix.get(i).getRevisionId() != full.get(i).getRevisionId()) {
                return false;
            }
        }
        return true;
    }
}

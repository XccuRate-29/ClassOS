package com.classos.attendance;

import com.classos.IntegrationTestBase;
import com.classos.attendance.AttendanceDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The two situations that matter in a real classroom: everyone scans at once, and the teacher closes mid-burst. */
class AttendanceConcurrencyTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired AttendanceSessionService sessions;
    @Autowired AttendanceMarkingService marking;

    @Test
    void sixtyStudentsScanningAtOnceAreAllRecordedExactlyOnce() throws Exception {
        int n = 60;
        AttendanceFixture f = new AttendanceFixture(jdbc, n);
        long s = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();

        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        long started = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long student = f.students.get(i);
            String device = f.devices.get(i);
            futures.add(pool.submit(() -> {
                go.await();
                // Read the code as late as possible, like a real scan.
                String outcome = marking.mark(student, new MarkRequest(s, sessions.currentCode(s), Method.QR),
                        device, "127.0.0.1").outcome();
                outcomes.computeIfAbsent(outcome, k -> new AtomicInteger()).incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> fu : futures) fu.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertEquals(n, outcomes.getOrDefault("ACCEPTED", new AtomicInteger()).get(), "outcomes: " + outcomes);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE session_id = ?", Integer.class, s);
        assertEquals(n, rows);
        System.out.printf("%d concurrent marks recorded in %d ms%n", n, ms);
    }

    @Test
    void closingDuringABurstLeavesEveryStudentWithExactlyOneConsistentRecord() throws Exception {
        int n = 40;
        AttendanceFixture f = new AttendanceFixture(jdbc, n);
        long s = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();

        AtomicInteger accepted = new AtomicInteger(), closed = new AtomicInteger(), other = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long student = f.students.get(i);
            String device = f.devices.get(i);
            futures.add(pool.submit(() -> {
                go.await();
                String o = marking.mark(student, new MarkRequest(s, sessions.currentCode(s), Method.QR),
                        device, "127.0.0.1").outcome();
                switch (o) {
                    case "ACCEPTED" -> accepted.incrementAndGet();
                    case "SESSION_CLOSED" -> closed.incrementAndGet();
                    default -> other.incrementAndGet();
                }
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            go.await();
            Thread.sleep(5);  // let some marks in first
            sessions.close(f.teacher, s);
            return null;
        }));
        go.countDown();
        for (Future<?> fu : futures) fu.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, other.get());
        assertEquals(n, accepted.get() + closed.get());
        SessionDetail d = sessions.detail(f.teacher, s);
        assertEquals("CLOSED", d.status());
        // Every accepted mark survived the close, and everyone else is absent: no gaps, no duplicates.
        assertEquals(accepted.get(), d.presentCount());
        assertEquals(n - accepted.get(), d.students().stream().filter(r -> "ABSENT".equals(r.status())).count());
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE session_id = ?", Integer.class, s);
        assertEquals(n, rows);
    }
}

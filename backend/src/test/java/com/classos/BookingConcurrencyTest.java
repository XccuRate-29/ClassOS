package com.classos;

import com.classos.booking.BookingDtos.BookingRequest;
import com.classos.booking.BookingService;
import com.classos.common.ApiException;
import com.classos.timetable.TimetableDtos.SlotKind;
import com.classos.timetable.TimetableDtos.SlotRequest;
import com.classos.timetable.TimetableService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BookingConcurrencyTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired BookingService bookings;
    @Autowired TimetableService timetable;

    private static LocalDate nextWeek() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(7);
    }

    /**
     * Many users press "Book" for the same room and time at the same moment.
     * Exactly one must win; every other request must be told the room is taken.
     */
    @Test
    void concurrentBookingsForTheSameRoomAllowExactlyOneWinner() throws Exception {
        Fixtures f = new Fixtures(jdbc);
        long room = f.room("LABX", 60, "LAB");
        int threads = 8;
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < threads; i++) users.add(f.user("TEACHER"));
        BookingRequest req = new BookingRequest(room, nextWeek(), LocalTime.of(14, 0), LocalTime.of(16, 0), "Extra lab");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger(), toldTaken = new AtomicInteger(), other = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (long user : users) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                try {
                    bookings.create(user, req);
                    won.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT) toldTaken.incrementAndGet(); else other.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    toldTaken.incrementAndGet();  // the exclusion constraint caught it
                }
                return null;
            }));
        }
        startTogether.countDown();
        for (Future<?> fu : futures) fu.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, won.get());
        assertEquals(threads - 1, toldTaken.get());
        assertEquals(0, other.get());
        Integer stored = jdbc.queryForObject("SELECT count(*) FROM room_bookings WHERE room_id = ?", Integer.class, room);
        assertEquals(1, stored);
    }

    @Test
    void bookingThatOverlapsAWeeklyClassIsRejected() {
        Fixtures f = new Fixtures(jdbc);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        long term = f.term(today.minusDays(1), today.plusDays(60), 5);
        long section = f.section(term, "A", 30);
        long teacher = f.user("TEACHER");
        long offering = f.offering(term, f.course("C", 3, 0), section, teacher);
        long room = f.room("R", 60, "CLASSROOM");
        timetable.create(new SlotRequest(offering, room, 3, LocalTime.of(10, 0), LocalTime.of(11, 0), SlotKind.LECTURE, true));

        LocalDate nextWednesday = today.with(TemporalAdjusters.next(java.time.DayOfWeek.WEDNESDAY));
        ApiException e = assertThrows(ApiException.class, () -> bookings.create(f.user("LAB_ASSISTANT"),
                new BookingRequest(room, nextWednesday, LocalTime.of(10, 30), LocalTime.of(12, 0), "Workshop")));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());

        // Same time on Thursday is free.
        assertDoesNotThrow(() -> bookings.create(f.user("LAB_ASSISTANT"),
                new BookingRequest(room, nextWednesday.plusDays(1), LocalTime.of(10, 30), LocalTime.of(12, 0), "Workshop")));
    }

    @Test
    void pastDatesAreRejected() {
        Fixtures f = new Fixtures(jdbc);
        long room = f.room("P", 60, "CLASSROOM");
        ApiException e = assertThrows(ApiException.class, () -> bookings.create(f.user("TEACHER"),
                new BookingRequest(room, LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1),
                        LocalTime.of(9, 0), LocalTime.of(10, 0), "Late")));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }
}

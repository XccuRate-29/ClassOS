package com.classos.booking;

import com.classos.booking.BookingDtos.*;
import com.classos.common.ApiException;
import com.classos.user.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Ad-hoc room bookings, checked against both other bookings and the weekly timetable.
 *
 * Why the row lock: "is the room free?" spans two tables (room_bookings and
 * timetable_slots), so no single constraint can answer it. Two requests could both
 * check, both see "free" and both insert (a check-then-act race). Locking the room's
 * row with SELECT ... FOR UPDATE makes requests for the same room take turns, like a
 * mutex per room; requests for different rooms still run in parallel.
 */
@Service
public class BookingService {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Kolkata");

    private static final String BOOKING_SELECT = """
            SELECT b.id, b.room_id, r.code, b.booked_by, u.full_name, b.booking_date,
                   b.start_time, b.end_time, b.purpose
            FROM room_bookings b JOIN rooms r ON r.id = b.room_id JOIN users u ON u.id = b.booked_by
            """;

    private static final RowMapper<BookingView> MAPPER = (rs, i) -> new BookingView(
            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
            rs.getObject(6, LocalDate.class), rs.getObject(7, LocalTime.class), rs.getObject(8, LocalTime.class),
            rs.getString(9));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired  // two constructors: tell Spring which one to use
    public BookingService(JdbcTemplate jdbc) {
        this(jdbc, Clock.system(CAMPUS_ZONE));
    }

    BookingService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<RoomOption> rooms() {
        return jdbc.query("SELECT id, code, building, capacity, room_type FROM rooms WHERE active ORDER BY room_type, code",
                (rs, i) -> new RoomOption(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)));
    }

    /** Everything occupying a room on a date: weekly classes and bookings. */
    @Transactional(readOnly = true)
    public RoomDay roomDay(long roomId, LocalDate date) {
        return new RoomDay(roomId, date, busy(roomId, date, LocalTime.MIN, LocalTime.of(23, 59, 59)));
    }

    private List<BookingConflict> busy(long roomId, LocalDate date, LocalTime start, LocalTime end) {
        List<BookingConflict> out = new ArrayList<>();
        // Weekly classes, in the term that contains the date (if any).
        jdbc.query("""
                SELECT c.code, sec.name, ts.start_time, ts.end_time
                FROM timetable_slots ts
                JOIN academic_terms t ON t.id = ts.term_id
                JOIN course_offerings o ON o.id = ts.offering_id
                JOIN courses c ON c.id = o.course_id
                JOIN sections sec ON sec.id = ts.section_id
                WHERE ts.room_id = ? AND ts.day_of_week = ?
                  AND CAST(? AS date) BETWEEN t.starts_on AND t.ends_on
                  AND ts.period && timerange(CAST(? AS time), CAST(? AS time), '[)')
                ORDER BY ts.start_time""",
                rs -> {
                    out.add(new BookingConflict("WEEKLY_CLASS", rs.getString(1) + " for " + rs.getString(2),
                            rs.getObject(3, LocalTime.class), rs.getObject(4, LocalTime.class)));
                },
                roomId, date.getDayOfWeek().getValue(), date, start, end);
        jdbc.query("""
                SELECT b.purpose, u.full_name, b.start_time, b.end_time
                FROM room_bookings b JOIN users u ON u.id = b.booked_by
                WHERE b.room_id = ? AND b.booking_date = ?
                  AND b.period && timerange(CAST(? AS time), CAST(? AS time), '[)')
                ORDER BY b.start_time""",
                rs -> {
                    out.add(new BookingConflict("BOOKING", rs.getString(1) + " (booked by " + rs.getString(2) + ")",
                            rs.getObject(3, LocalTime.class), rs.getObject(4, LocalTime.class)));
                },
                roomId, date, start, end);
        out.sort((a, b) -> a.startTime().compareTo(b.startTime()));
        return out;
    }

    @Transactional
    public BookingView create(long userId, BookingRequest req) {
        if (!req.endTime().isAfter(req.startTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "End time must be after start time.");
        }
        if (req.date().isBefore(LocalDate.now(clock))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bookings can't be made for past dates.");
        }

        // The per-room "mutex". Concurrent bookings for this room wait here until this
        // transaction commits, then see its booking in the check below.
        List<Boolean> room = jdbc.query("SELECT active FROM rooms WHERE id = ? FOR UPDATE",
                (rs, i) -> rs.getBoolean(1), req.roomId());
        if (room.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Room not found.");
        if (!room.get(0)) throw new ApiException(HttpStatus.BAD_REQUEST, "This room is not in use.");

        List<BookingConflict> conflicts = busy(req.roomId(), req.date(), req.startTime(), req.endTime());
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "The room is not free for the whole time you picked.")
                    .with("conflicts", conflicts);
        }

        Long id = jdbc.queryForObject("""
                INSERT INTO room_bookings (room_id, booked_by, booking_date, start_time, end_time, purpose)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, req.roomId(), userId, req.date(), req.startTime(), req.endTime(), req.purpose().trim());
        return jdbc.queryForObject(BOOKING_SELECT + " WHERE b.id = ?", MAPPER, id);
    }

    public List<BookingView> mine(long userId) {
        return jdbc.query(BOOKING_SELECT + " WHERE b.booked_by = ? AND b.booking_date >= ? ORDER BY b.booking_date, b.start_time",
                MAPPER, userId, LocalDate.now(clock));
    }

    /** Owners can cancel their own bookings; admins can cancel any. */
    @Transactional
    public void cancel(long bookingId, long userId, Role role) {
        List<Long> owner = jdbc.query("SELECT booked_by FROM room_bookings WHERE id = ?", (rs, i) -> rs.getLong(1), bookingId);
        if (owner.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Booking not found.");
        if (role != Role.ADMIN && owner.get(0) != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only cancel your own bookings.");
        }
        jdbc.update("DELETE FROM room_bookings WHERE id = ?", bookingId);
    }
}

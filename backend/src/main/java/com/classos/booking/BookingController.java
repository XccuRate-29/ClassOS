package com.classos.booking;

import com.classos.booking.BookingDtos.*;
import com.classos.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// SecurityConfig limits /api/bookings/** to TEACHER, LAB_ASSISTANT and ADMIN.
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping("/rooms")
    public List<RoomOption> rooms() {
        return bookings.rooms();
    }

    @GetMapping("/room-day")
    public RoomDay roomDay(@RequestParam long roomId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return bookings.roomDay(roomId, date);
    }

    @GetMapping("/mine")
    public List<BookingView> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return bookings.mine(user.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody BookingRequest req) {
        return bookings.create(user.id(), req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        bookings.cancel(id, user.id(), user.role());
    }
}

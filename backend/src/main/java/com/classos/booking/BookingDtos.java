package com.classos.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class BookingDtos {
    private BookingDtos() {}

    public record BookingRequest(@NotNull Long roomId,
                                 @NotNull LocalDate date,
                                 @NotNull LocalTime startTime,
                                 @NotNull LocalTime endTime,
                                 @NotBlank @Size(max = 200) String purpose) {}

    public record BookingView(long id, long roomId, String roomCode, long bookedById, String bookedByName,
                              LocalDate date, LocalTime startTime, LocalTime endTime, String purpose) {}

    /** type: WEEKLY_CLASS (from the timetable) or BOOKING (another ad-hoc booking). */
    public record BookingConflict(String type, String description, LocalTime startTime, LocalTime endTime) {}

    public record RoomDay(long roomId, LocalDate date, List<BookingConflict> busy) {}

    public record RoomOption(long id, String code, String building, int capacity, String roomType) {}
}

package com.classos.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// /api/admin/** is ADMIN-only.
@RestController
@RequestMapping("/api/admin/attendance")
public class AdminAttendanceController {

    private final AttendanceMarkingService marking;

    public AdminAttendanceController(AttendanceMarkingService marking) {
        this.marking = marking;
    }

    /** When a student changes phones: their next successful mark registers the new one. */
    @DeleteMapping("/devices/{studentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetDevice(@PathVariable long studentId) {
        marking.resetDevice(studentId);
    }
}

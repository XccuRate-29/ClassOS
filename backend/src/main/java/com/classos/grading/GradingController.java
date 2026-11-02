package com.classos.grading;

import com.classos.grading.GradingDtos.*;
import com.classos.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.*;

// /api/teacher/** is TEACHER and ADMIN; the queue is always the caller's own courses.
@RestController
@RequestMapping("/api/teacher/grading")
public class GradingController {

    private final GradingQueueService queue;

    public GradingController(GradingQueueService queue) {
        this.queue = queue;
    }

    @GetMapping("/queue")
    public QueueView queue(@org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedUser u,
                           @RequestParam(defaultValue = "FCFS") Policy policy,
                           @RequestParam(required = false) Double agingPerHour,
                           @RequestParam(required = false) Integer quantum) {
        return queue.queue(u.id(), policy, agingPerHour, quantum);
    }

    @GetMapping("/compare")
    public CompareView compare(@org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedUser u,
                               @RequestParam(required = false) Double agingPerHour,
                               @RequestParam(required = false) Integer quantum) {
        return queue.compare(u.id(), agingPerHour, quantum);
    }
}

package com.classos.timetable;

import com.classos.common.ApiException;
import com.classos.security.AuthenticatedUser;
import com.classos.timetable.TimetableDtos.*;
import com.classos.timetable.TimetableService.Filter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// /api/admin/** is ADMIN-only (SecurityConfig).
@RestController
@RequestMapping("/api/admin/timetable")
public class TimetableAdminController {

    private final TimetableService timetable;
    private final GenerationService generation;

    public TimetableAdminController(TimetableService timetable, GenerationService generation) {
        this.timetable = timetable;
        this.generation = generation;
    }

    @GetMapping("/setup")
    public SetupView setup(@RequestParam(required = false) Long termId) {
        return timetable.setup(termId);
    }

    /** Exactly one of sectionId / teacherId / roomId selects the view. */
    @GetMapping
    public TimetableView view(@RequestParam(required = false) Long termId,
                              @RequestParam(required = false) Long sectionId,
                              @RequestParam(required = false) Long teacherId,
                              @RequestParam(required = false) Long roomId) {
        TermInfo term = timetable.resolveTerm(termId);
        if (sectionId != null) return timetable.view(term, Filter.SECTION, sectionId);
        if (teacherId != null) return timetable.view(term, Filter.TEACHER, teacherId);
        if (roomId != null) return timetable.view(term, Filter.ROOM, roomId);
        throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a section, teacher or room.");
    }

    @PostMapping("/check")
    public CheckResult check(@Valid @RequestBody SlotRequest req, @RequestParam(required = false) Long excludeSlotId) {
        return timetable.check(req, excludeSlotId);
    }

    @PostMapping("/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public SlotView create(@Valid @RequestBody SlotRequest req) {
        return timetable.create(req);
    }

    @PutMapping("/slots/{id}")
    public SlotView move(@PathVariable long id, @Valid @RequestBody SlotRequest req) {
        return timetable.move(id, req);
    }

    @DeleteMapping("/slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        timetable.delete(id);
    }

    @PatchMapping("/slots/{id}/lock")
    public SlotView lock(@PathVariable long id, @RequestBody LockRequest req) {
        return timetable.setLocked(id, req.locked());
    }

    @PostMapping("/generate")
    public GenerationResult generate(@AuthenticationPrincipal AuthenticatedUser user,
                                     @RequestBody(required = false) GenerateRequest req) {
        return generation.generate(req == null ? null : req.termId(), user.id());
    }

    @GetMapping("/runs")
    public List<RunView> runs(@RequestParam(required = false) Long termId) {
        return generation.runs(timetable.resolveTerm(termId).id());
    }
}

package com.classos.setup;

import com.classos.setup.SetupDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// /api/admin/** is ADMIN-only (SecurityConfig).
@RestController
@RequestMapping("/api/admin/setup")
public class SetupController {

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    // departments
    @GetMapping("/departments")
    public List<DepartmentView> departments() { return setup.departments(); }

    @PostMapping("/departments") @ResponseStatus(HttpStatus.CREATED)
    public DepartmentView createDepartment(@Valid @RequestBody DepartmentRequest r) { return setup.createDepartment(r); }

    @PutMapping("/departments/{id}")
    public DepartmentView updateDepartment(@PathVariable long id, @Valid @RequestBody DepartmentRequest r) {
        return setup.updateDepartment(id, r);
    }

    // terms and period grid
    @GetMapping("/terms")
    public List<TermView> terms() { return setup.terms(); }

    @PostMapping("/terms") @ResponseStatus(HttpStatus.CREATED)
    public TermView createTerm(@Valid @RequestBody TermRequest r) { return setup.createTerm(r); }

    @PutMapping("/terms/{id}")
    public TermView updateTerm(@PathVariable long id, @Valid @RequestBody TermRequest r) { return setup.updateTerm(id, r); }

    @PostMapping("/terms/{id}/current")
    public TermView makeCurrent(@PathVariable long id) { return setup.makeCurrent(id); }

    @PutMapping("/terms/{id}/periods")
    public TermView replacePeriods(@PathVariable long id, @Valid @RequestBody PeriodGridRequest r) {
        return setup.replacePeriods(id, r);
    }

    // rooms
    @GetMapping("/rooms")
    public List<RoomView> rooms() { return setup.rooms(); }

    @PostMapping("/rooms") @ResponseStatus(HttpStatus.CREATED)
    public RoomView createRoom(@Valid @RequestBody RoomRequest r) { return setup.createRoom(r); }

    @PutMapping("/rooms/{id}")
    public RoomView updateRoom(@PathVariable long id, @Valid @RequestBody RoomRequest r) { return setup.updateRoom(id, r); }

    // courses
    @GetMapping("/courses")
    public List<CourseView> courses() { return setup.courses(); }

    @PostMapping("/courses") @ResponseStatus(HttpStatus.CREATED)
    public CourseView createCourse(@Valid @RequestBody CourseRequest r) { return setup.createCourse(r); }

    @PutMapping("/courses/{id}")
    public CourseView updateCourse(@PathVariable long id, @Valid @RequestBody CourseRequest r) {
        return setup.updateCourse(id, r);
    }

    // sections and students
    @GetMapping("/sections")
    public List<SectionView> sections(@RequestParam long termId) { return setup.sections(termId); }

    @PostMapping("/sections") @ResponseStatus(HttpStatus.CREATED)
    public SectionView createSection(@Valid @RequestBody SectionRequest r) { return setup.createSection(r); }

    @PutMapping("/sections/{id}")
    public SectionView updateSection(@PathVariable long id, @Valid @RequestBody SectionRequest r) {
        return setup.updateSection(id, r);
    }

    @DeleteMapping("/sections/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSection(@PathVariable long id) { setup.deleteSection(id); }

    @GetMapping("/sections/{id}/students")
    public List<StudentView> students(@PathVariable long id) { return setup.students(id); }

    @PostMapping("/sections/{id}/students")
    public EnrollResult enroll(@PathVariable long id, @Valid @RequestBody EnrollRequest r) { return setup.enroll(id, r); }

    @DeleteMapping("/sections/{id}/students/{studentId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@PathVariable long id, @PathVariable long studentId) { setup.unenroll(id, studentId); }

    // offerings
    @GetMapping("/teachers")
    public List<TeacherView> teachers() { return setup.teachers(); }

    @GetMapping("/offerings")
    public List<OfferingView> offerings(@RequestParam long termId) { return setup.offerings(termId); }

    @PostMapping("/offerings") @ResponseStatus(HttpStatus.CREATED)
    public OfferingView createOffering(@Valid @RequestBody OfferingRequest r) { return setup.createOffering(r); }

    @PutMapping("/offerings/{id}/teacher")
    public OfferingView changeTeacher(@PathVariable long id, @Valid @RequestBody TeacherChangeRequest r) {
        return setup.changeTeacher(id, r);
    }

    @DeleteMapping("/offerings/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOffering(@PathVariable long id) { setup.deleteOffering(id); }

    // teacher unavailability
    @GetMapping("/unavailability")
    public List<UnavailabilityView> unavailability(@RequestParam long termId) { return setup.unavailability(termId); }

    @PostMapping("/unavailability") @ResponseStatus(HttpStatus.CREATED)
    public void addUnavailability(@Valid @RequestBody UnavailabilityRequest r) { setup.addUnavailability(r); }

    @DeleteMapping("/unavailability/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUnavailability(@PathVariable long id) { setup.deleteUnavailability(id); }
}

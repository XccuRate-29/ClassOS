package com.classos.workspace;

import com.classos.security.AuthenticatedUser;
import com.classos.workspace.WorkspaceDtos.*;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Any signed-in user may call these; WorkspaceService checks per course whether the
 * caller is its teacher, an enrolled student, or an admin.
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final WorkspaceService workspace;

    public WorkspaceController(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/courses")
    public List<CourseCard> courses(@AuthenticationPrincipal AuthenticatedUser u) {
        return workspace.courses(u);
    }

    @GetMapping("/offerings/{id}")
    public CourseWorkspace course(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) {
        return workspace.workspace(id, u);
    }

    @PostMapping(value = "/offerings/{id}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialView addMaterial(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id,
                                    @RequestParam String title, @RequestParam(required = false) String description,
                                    @RequestPart("file") MultipartFile file) {
        return workspace.addMaterial(id, u, title, description, file);
    }

    @DeleteMapping("/materials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMaterial(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) {
        workspace.deleteMaterial(id, u);
    }

    @GetMapping("/materials/{id}/file")
    public ResponseEntity<Resource> material(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) {
        return workspace.downloadMaterial(id, u);
    }

    /** Multipart: a JSON part "data" (AssignmentRequest) and an optional "file" part. */
    @PostMapping(value = "/offerings/{id}/assignments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentView createAssignment(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id,
                                           @Valid @RequestPart("data") AssignmentRequest data,
                                           @RequestPart(value = "file", required = false) MultipartFile file) {
        return workspace.createAssignment(id, u, data, file);
    }

    @PutMapping("/assignments/{id}")
    public AssignmentView updateAssignment(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id,
                                           @Valid @RequestBody AssignmentRequest data) {
        return workspace.updateAssignment(id, u, data);
    }

    @DeleteMapping("/assignments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAssignment(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) {
        workspace.deleteAssignment(id, u);
    }

    @GetMapping("/assignments/{id}")
    public AssignmentDetail assignment(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) {
        return workspace.detail(id, u);
    }

    @GetMapping("/assignments/{id}/attachment")
    public ResponseEntity<Resource> attachment(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) {
        return workspace.downloadAttachment(id, u);
    }

    @PostMapping(value = "/assignments/{id}/submission", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MySubmission submit(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id,
                               @RequestPart("file") MultipartFile file) {
        return workspace.submit(id, u, file);
    }

    @GetMapping("/submissions/{id}/file")
    public ResponseEntity<Resource> submission(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) {
        return workspace.downloadSubmission(id, u);
    }

    @PostMapping("/submissions/{id}/grade")
    public SubmissionRow grade(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id,
                               @Valid @RequestBody GradeRequest r) {
        return workspace.grade(id, u, r);
    }
}

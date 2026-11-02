package com.classos.workspace;

import com.classos.common.ApiException;
import com.classos.files.FileStorageService;
import com.classos.files.StoredFile;
import com.classos.security.AuthenticatedUser;
import com.classos.user.Role;
import com.classos.workspace.CourseAccess.Offering;
import com.classos.workspace.WorkspaceDtos.*;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The digital classroom: materials, assignments, submissions and grading for one
 * course offering. Every method checks access through CourseAccess first.
 */
@Service
public class WorkspaceService {

    private final JdbcTemplate jdbc;
    private final CourseAccess access;
    private final FileStorageService files;
    private final Clock clock;

    public WorkspaceService(JdbcTemplate jdbc, CourseAccess access, FileStorageService files, Clock campusClock) {
        this.jdbc = jdbc;
        this.access = access;
        this.files = files;
        this.clock = campusClock;
    }

    // ------------------------------------------------------------------ courses

    /** The courses this user teaches or studies in the current term (all of them, for an admin). */
    public List<CourseCard> courses(AuthenticatedUser u) {
        return cards(u, null);
    }

    /**
     * One card, by id, in a single query (whatever its term). Previously this loaded every
     * course of the user and picked one out: a refinement found in code review.
     */
    private CourseCard card(Offering o, AuthenticatedUser u) {
        return cards(u, o.id()).stream().findFirst()
                .orElse(new CourseCard(o.id(), o.courseCode(), o.courseTitle(), o.sectionName(), o.teacherName(), 0, 0, 0, 0));
    }

    private List<CourseCard> cards(AuthenticatedUser u, Long offeringId) {
        String who = switch (u.role()) {
            case TEACHER -> "o.teacher_id = ?";
            case STUDENT -> "EXISTS (SELECT 1 FROM section_enrollments e WHERE e.section_id = o.section_id AND e.term_id = o.term_id AND e.student_id = ?)";
            default -> "CAST(? AS bigint) IS NOT NULL";  // admin: every offering
        };
        // A list shows the current term; a single card is fetched by id whatever its term.
        String scope = offeringId == null ? " AND term.is_current" : " AND o.id = ?";
        Object[] args = offeringId == null ? new Object[]{u.id(), u.id()} : new Object[]{u.id(), u.id(), offeringId};
        return jdbc.query("""
                SELECT o.id, c.code, c.title, s.name, t.full_name,
                       (SELECT count(*) FROM materials m WHERE m.offering_id = o.id),
                       (SELECT count(*) FROM assignments a WHERE a.offering_id = o.id),
                       (SELECT count(*) FROM submissions sb JOIN assignments a ON a.id = sb.assignment_id
                        WHERE a.offering_id = o.id AND sb.status = 'SUBMITTED'),
                       (SELECT count(*) FROM assignments a
                        WHERE a.offering_id = o.id AND (a.due_at > now() OR a.accept_late)
                          AND NOT EXISTS (SELECT 1 FROM submissions sb WHERE sb.assignment_id = a.id AND sb.student_id = ?))
                FROM course_offerings o
                JOIN courses c ON c.id = o.course_id
                JOIN sections s ON s.id = o.section_id
                JOIN users t ON t.id = o.teacher_id
                JOIN academic_terms term ON term.id = o.term_id
                WHERE """ + who + scope + " ORDER BY s.name, c.code",
                (rs, i) -> new CourseCard(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7),
                        u.role() == Role.STUDENT ? 0 : rs.getInt(8),
                        u.role() == Role.STUDENT ? rs.getInt(9) : 0),
                args);
    }

    @Transactional(readOnly = true)
    public CourseWorkspace workspace(long offeringId, AuthenticatedUser u) {
        Offering o = access.requireRead(offeringId, u);
        List<MaterialView> materials = jdbc.query("""
                SELECT m.id, m.title, m.description, f.original_name, f.size_bytes, m.created_at
                FROM materials m JOIN stored_files f ON f.id = m.file_id
                WHERE m.offering_id = ? ORDER BY m.created_at DESC""",
                (rs, i) -> new MaterialView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getObject(6, OffsetDateTime.class)), offeringId);
        List<AssignmentView> assignments = jdbc.query(ASSIGNMENT_SELECT + " WHERE a.offering_id = ? ORDER BY a.due_at DESC",
                assignmentMapper(u), u.id(), offeringId);
        return new CourseWorkspace(card(o, u), access.teaches(o, u), materials, assignments);
    }

    // ------------------------------------------------------------------ materials

    @Transactional
    public MaterialView addMaterial(long offeringId, AuthenticatedUser u, String title, String description, MultipartFile file) {
        access.requireTeach(offeringId, u);
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Give the material a title (up to 200 characters).");
        }
        StoredFile f = files.store(file, u.id());
        Long id = jdbc.queryForObject("""
                INSERT INTO materials (offering_id, title, description, file_id, created_by)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class,
                offeringId, title.trim(), description == null || description.isBlank() ? null : description.trim(), f.id(), u.id());
        return new MaterialView(id, title.trim(), description, f.originalName(), f.sizeBytes(), OffsetDateTime.now(clock));
    }

    private record Owned(long offeringId, UUID fileId) {}

    private Owned material(long materialId) {
        List<Owned> found = jdbc.query("SELECT offering_id, file_id FROM materials WHERE id = ?",
                (rs, i) -> new Owned(rs.getLong(1), rs.getObject(2, UUID.class)), materialId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Material not found.");
        return found.get(0);
    }

    @Transactional
    public void deleteMaterial(long materialId, AuthenticatedUser u) {
        Owned m = material(materialId);
        access.requireTeach(m.offeringId(), u);
        jdbc.update("DELETE FROM materials WHERE id = ?", materialId);
        files.deleteAfterCommit(m.fileId());
    }

    public ResponseEntity<Resource> downloadMaterial(long materialId, AuthenticatedUser u) {
        Owned m = material(materialId);
        access.requireRead(m.offeringId(), u);
        return files.download(m.fileId());
    }

    // ------------------------------------------------------------------ assignments

    private static final String ASSIGNMENT_SELECT = """
            SELECT a.id, a.offering_id, a.title, a.instructions, f.original_name, a.due_at, a.grade_by, a.max_marks,
                   a.accept_late, a.priority, a.est_minutes,
                   (SELECT count(*) FROM submissions s WHERE s.assignment_id = a.id),
                   (SELECT count(*) FROM submissions s WHERE s.assignment_id = a.id AND s.status = 'GRADED'),
                   mine.id, mf.original_name, mine.submitted_at, mine.late, mine.version, mine.status, mine.marks, mine.feedback
            FROM assignments a
            LEFT JOIN stored_files f ON f.id = a.attachment_id
            LEFT JOIN submissions mine ON mine.assignment_id = a.id AND mine.student_id = ?
            LEFT JOIN stored_files mf ON mf.id = mine.file_id""";

    /** Students see their own submission; teachers see counts. Feedback is shown only once graded. */
    private RowMapper<AssignmentView> assignmentMapper(AuthenticatedUser u) {
        return (rs, i) -> {
            MySubmission mine = null;
            if (u.role() == Role.STUDENT && rs.getObject(14) != null) {
                boolean graded = "GRADED".equals(rs.getString(19));
                mine = new MySubmission(rs.getLong(14), rs.getString(15), rs.getObject(16, OffsetDateTime.class),
                        rs.getBoolean(17), rs.getInt(18), rs.getString(19),
                        graded ? rs.getBigDecimal(20) : null, graded ? rs.getString(21) : null);
            }
            return new AssignmentView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class), rs.getBigDecimal(8),
                    rs.getBoolean(9), rs.getInt(10), rs.getInt(11),
                    u.role() == Role.STUDENT ? 0 : rs.getInt(12), u.role() == Role.STUDENT ? 0 : rs.getInt(13), mine);
        };
    }

    private AssignmentView assignment(long assignmentId, AuthenticatedUser u) {
        List<AssignmentView> found = jdbc.query(ASSIGNMENT_SELECT + " WHERE a.id = ?", assignmentMapper(u), u.id(), assignmentId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Assignment not found.");
        return found.get(0);
    }

    private static OffsetDateTime gradeBy(AssignmentRequest r) {
        return r.gradeBy() != null ? r.gradeBy() : r.dueAt().plusDays(7);
    }

    @Transactional
    public AssignmentView createAssignment(long offeringId, AuthenticatedUser u, AssignmentRequest r, MultipartFile attachment) {
        access.requireTeach(offeringId, u);
        if (gradeBy(r).isBefore(r.dueAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The grading deadline must be on or after the due date.");
        }
        UUID fileId = attachment == null || attachment.isEmpty() ? null : files.store(attachment, u.id()).id();
        Long id = jdbc.queryForObject("""
                INSERT INTO assignments (offering_id, title, instructions, attachment_id, due_at, grade_by, max_marks,
                                         accept_late, priority, est_minutes, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                offeringId, r.title().trim(), r.instructions() == null ? "" : r.instructions(), fileId, r.dueAt(),
                gradeBy(r), r.maxMarks(), r.acceptLate() == null || r.acceptLate(),
                r.priority() == null ? 2 : r.priority(), r.estMinutes() == null ? 10 : r.estMinutes(), u.id());
        return assignment(id, u);
    }

    private long offeringOfAssignment(long assignmentId) {
        List<Long> found = jdbc.query("SELECT offering_id FROM assignments WHERE id = ?", (rs, i) -> rs.getLong(1), assignmentId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Assignment not found.");
        return found.get(0);
    }

    @Transactional
    public AssignmentView updateAssignment(long assignmentId, AuthenticatedUser u, AssignmentRequest r) {
        access.requireTeach(offeringOfAssignment(assignmentId), u);
        BigDecimal highest = jdbc.queryForObject(
                "SELECT coalesce(max(marks), 0) FROM submissions WHERE assignment_id = ?", BigDecimal.class, assignmentId);
        if (highest != null && r.maxMarks().compareTo(highest) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Someone already has " + highest.stripTrailingZeros().toPlainString()
                    + " marks; the maximum can't go below that.");
        }
        jdbc.update("""
                UPDATE assignments SET title = ?, instructions = ?, due_at = ?, grade_by = ?, max_marks = ?,
                       accept_late = ?, priority = ?, est_minutes = ? WHERE id = ?""",
                r.title().trim(), r.instructions() == null ? "" : r.instructions(), r.dueAt(), gradeBy(r), r.maxMarks(),
                r.acceptLate() == null || r.acceptLate(), r.priority() == null ? 2 : r.priority(),
                r.estMinutes() == null ? 10 : r.estMinutes(), assignmentId);
        return assignment(assignmentId, u);
    }

    /** Refused once anyone has submitted: deleting would silently destroy students' work. */
    @Transactional
    public void deleteAssignment(long assignmentId, AuthenticatedUser u) {
        access.requireTeach(offeringOfAssignment(assignmentId), u);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM submissions WHERE assignment_id = ?", Integer.class, assignmentId);
        if (n != null && n > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Students have already submitted this assignment, so it can't be deleted.");
        }
        UUID attachment = jdbc.queryForObject("SELECT attachment_id FROM assignments WHERE id = ?", UUID.class, assignmentId);
        jdbc.update("DELETE FROM assignments WHERE id = ?", assignmentId);
        if (attachment != null) files.deleteAfterCommit(attachment);
    }

    @Transactional(readOnly = true)
    public AssignmentDetail detail(long assignmentId, AuthenticatedUser u) {
        Offering o = access.requireRead(offeringOfAssignment(assignmentId), u);
        boolean teaching = access.teaches(o, u) || u.role() == Role.ADMIN;
        List<SubmissionRow> subs = !teaching ? List.of() : jdbc.query("""
                SELECT s.id, s.student_id, u.full_name, u.email, f.original_name, s.submitted_at, s.late, s.version,
                       s.status, s.marks, s.feedback
                FROM submissions s JOIN users u ON u.id = s.student_id JOIN stored_files f ON f.id = s.file_id
                WHERE s.assignment_id = ? ORDER BY s.submitted_at""",
                (rs, i) -> new SubmissionRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getBoolean(7), rs.getInt(8),
                        rs.getString(9), rs.getBigDecimal(10), rs.getString(11)), assignmentId);
        List<StudentRef> missing = !teaching ? List.of() : jdbc.query("""
                SELECT u.id, u.full_name, u.email FROM section_enrollments e JOIN users u ON u.id = e.student_id
                WHERE e.section_id = ? AND e.term_id = ?
                  AND NOT EXISTS (SELECT 1 FROM submissions s WHERE s.assignment_id = ? AND s.student_id = e.student_id)
                ORDER BY u.full_name""",
                (rs, i) -> new StudentRef(rs.getLong(1), rs.getString(2), rs.getString(3)), o.sectionId(), o.termId(), assignmentId);
        return new AssignmentDetail(assignment(assignmentId, u), card(o, u), access.teaches(o, u), subs, missing);
    }

    public ResponseEntity<Resource> downloadAttachment(long assignmentId, AuthenticatedUser u) {
        access.requireRead(offeringOfAssignment(assignmentId), u);
        UUID id = jdbc.queryForObject("SELECT attachment_id FROM assignments WHERE id = ?", UUID.class, assignmentId);
        if (id == null) throw new ApiException(HttpStatus.NOT_FOUND, "This assignment has no attachment.");
        return files.download(id);
    }

    // ------------------------------------------------------------------ submissions

    private record AssignmentRules(long offeringId, OffsetDateTime dueAt, boolean acceptLate, BigDecimal maxMarks) {}

    private AssignmentRules rules(long assignmentId) {
        List<AssignmentRules> found = jdbc.query("SELECT offering_id, due_at, accept_late, max_marks FROM assignments WHERE id = ?",
                (rs, i) -> new AssignmentRules(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getBoolean(3),
                        rs.getBigDecimal(4)), assignmentId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Assignment not found.");
        return found.get(0);
    }

    /**
     * First submission inserts; a resubmission replaces the file and bumps version, which
     * invalidates any grade a teacher is in the middle of entering for the old version.
     * The row is locked FOR UPDATE so two tabs resubmitting at once take turns.
     */
    @Transactional
    public MySubmission submit(long assignmentId, AuthenticatedUser u, MultipartFile file) {
        if (u.role() != Role.STUDENT) throw new ApiException(HttpStatus.FORBIDDEN, "Only students submit assignments.");
        AssignmentRules a = rules(assignmentId);
        access.requireRead(a.offeringId(), u);
        boolean late = OffsetDateTime.now(clock).isAfter(a.dueAt());
        if (late && !a.acceptLate()) {
            throw new ApiException(HttpStatus.CONFLICT, "The deadline has passed and this assignment doesn't accept late work.");
        }

        List<Object[]> existing = jdbc.query(
                "SELECT id, status, file_id FROM submissions WHERE assignment_id = ? AND student_id = ? FOR UPDATE",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class)}, assignmentId, u.id());
        if (!existing.isEmpty() && "GRADED".equals(existing.get(0)[1])) {
            throw new ApiException(HttpStatus.CONFLICT, "This has already been graded. Ask your teacher if you need to resubmit.");
        }

        StoredFile f = files.store(file, u.id());
        long id;
        if (existing.isEmpty()) {
            id = jdbc.queryForObject("""
                    INSERT INTO submissions (assignment_id, student_id, file_id, late) VALUES (?, ?, ?, ?) RETURNING id""",
                    Long.class, assignmentId, u.id(), f.id(), late);
        } else {
            id = (Long) existing.get(0)[0];
            jdbc.update("""
                    UPDATE submissions SET file_id = ?, submitted_at = now(), late = ?, version = version + 1
                    WHERE id = ?""", f.id(), late, id);
            files.deleteAfterCommit((UUID) existing.get(0)[2]);
        }
        return jdbc.queryForObject("""
                SELECT s.id, f.original_name, s.submitted_at, s.late, s.version, s.status
                FROM submissions s JOIN stored_files f ON f.id = s.file_id WHERE s.id = ?""",
                (rs, i) -> new MySubmission(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                        rs.getBoolean(4), rs.getInt(5), rs.getString(6), null, null), id);
    }

    private record SubmissionInfo(long assignmentId, long studentId, UUID fileId) {}

    private SubmissionInfo submission(long submissionId) {
        List<SubmissionInfo> found = jdbc.query("SELECT assignment_id, student_id, file_id FROM submissions WHERE id = ?",
                (rs, i) -> new SubmissionInfo(rs.getLong(1), rs.getLong(2), rs.getObject(3, UUID.class)), submissionId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Submission not found.");
        return found.get(0);
    }

    /** The submitting student, the course's teacher, or an admin. Anyone else gets 404. */
    public ResponseEntity<Resource> downloadSubmission(long submissionId, AuthenticatedUser u) {
        SubmissionInfo s = submission(submissionId);
        Offering o = access.offering(rules(s.assignmentId()).offeringId());
        boolean ok = s.studentId() == u.id() || access.teaches(o, u) || u.role() == Role.ADMIN;
        if (!ok) throw new ApiException(HttpStatus.NOT_FOUND, "Submission not found.");
        return files.download(s.fileId());
    }

    /**
     * Optimistic locking: the UPDATE only matches if the submission is still at the version
     * the teacher downloaded. If the student resubmitted in between, nothing is saved and the
     * teacher is told to look at the new file. Regrading the same version is allowed.
     */
    @Transactional
    public SubmissionRow grade(long submissionId, AuthenticatedUser u, GradeRequest r) {
        SubmissionInfo s = submission(submissionId);
        AssignmentRules a = rules(s.assignmentId());
        access.requireTeach(a.offeringId(), u);
        if (r.marks().compareTo(a.maxMarks()) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Marks can't be more than " + a.maxMarks().stripTrailingZeros().toPlainString() + ".");
        }
        int updated = jdbc.update("""
                UPDATE submissions SET status = 'GRADED', marks = ?, feedback = ?, graded_by = ?, graded_at = now()
                WHERE id = ? AND version = ?""",
                r.marks(), r.feedback() == null || r.feedback().isBlank() ? null : r.feedback().trim(), u.id(),
                submissionId, r.version());
        if (updated == 0) {
            Integer current = jdbc.queryForObject("SELECT version FROM submissions WHERE id = ?", Integer.class, submissionId);
            throw new ApiException(HttpStatus.CONFLICT,
                    "The student submitted a new version while you were grading. Download it and grade again.")
                    .with("currentVersion", current);
        }
        return jdbc.queryForObject("""
                SELECT s.id, s.student_id, u.full_name, u.email, f.original_name, s.submitted_at, s.late, s.version,
                       s.status, s.marks, s.feedback
                FROM submissions s JOIN users u ON u.id = s.student_id JOIN stored_files f ON f.id = s.file_id
                WHERE s.id = ?""",
                (rs, i) -> new SubmissionRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getBoolean(7), rs.getInt(8),
                        rs.getString(9), rs.getBigDecimal(10), rs.getString(11)), submissionId);
    }
}

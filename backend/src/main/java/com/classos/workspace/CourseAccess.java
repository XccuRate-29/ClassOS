package com.classos.workspace;

import com.classos.common.ApiException;
import com.classos.security.AuthenticatedUser;
import com.classos.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Who may see or change a course offering's workspace:
 *   read  = the offering's teacher, an admin, or a student enrolled in its section that term
 *   teach = the offering's teacher only
 * Someone without read access gets 404, not 403, so they cannot even learn the course exists.
 */
@Component
public class CourseAccess {

    public record Offering(long id, long termId, long sectionId, long teacherId, String courseCode,
                           String courseTitle, String sectionName, String teacherName) {}

    private final JdbcTemplate jdbc;

    public CourseAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Offering offering(long offeringId) {
        List<Offering> found = jdbc.query("""
                SELECT o.id, o.term_id, o.section_id, o.teacher_id, c.code, c.title, s.name, u.full_name
                FROM course_offerings o
                JOIN courses c ON c.id = o.course_id
                JOIN sections s ON s.id = o.section_id
                JOIN users u ON u.id = o.teacher_id
                WHERE o.id = ?""",
                (rs, i) -> new Offering(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)), offeringId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Course not found.");
        return found.get(0);
    }

    public boolean teaches(Offering o, AuthenticatedUser u) {
        return u.role() == Role.TEACHER && o.teacherId() == u.id();
    }

    public boolean enrolled(Offering o, long studentId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM section_enrollments WHERE section_id = ? AND term_id = ? AND student_id = ?",
                Integer.class, o.sectionId(), o.termId(), studentId);
        return n != null && n > 0;
    }

    public Offering requireRead(long offeringId, AuthenticatedUser u) {
        Offering o = offering(offeringId);
        boolean ok = u.role() == Role.ADMIN || teaches(o, u) || (u.role() == Role.STUDENT && enrolled(o, u.id()));
        if (!ok) throw new ApiException(HttpStatus.NOT_FOUND, "Course not found.");
        return o;
    }

    public Offering requireTeach(long offeringId, AuthenticatedUser u) {
        Offering o = requireRead(offeringId, u);
        if (!teaches(o, u)) throw new ApiException(HttpStatus.FORBIDDEN, "Only the course's teacher can do this.");
        return o;
    }
}

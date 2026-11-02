package com.classos.feedback;

import com.classos.common.ApiException;
import com.classos.feedback.FeedbackDtos.*;
import com.classos.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FeedbackService {

    private final JdbcTemplate jdbc;

    public FeedbackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void submit(AuthenticatedUser u, FeedbackRequest r) {
        jdbc.update("INSERT INTO feedback (user_id, role, page, category, rating, message) VALUES (?, ?, ?, ?, ?, ?)",
                u.id(), u.role().name(), r.page(), r.category(), r.rating(), r.message().trim());
    }

    public List<FeedbackView> list(String status, String category) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) { where.append(" AND f.status = ?"); args.add(status); }
        if (category != null && !category.isBlank()) { where.append(" AND f.category = ?"); args.add(category); }
        return jdbc.query("""
                SELECT f.id, u.full_name, f.role, f.page, f.category, f.rating, f.message, f.status, f.created_at
                FROM feedback f JOIN users u ON u.id = f.user_id""" + where + " ORDER BY f.created_at DESC LIMIT 500",
                (rs, i) -> new FeedbackView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Integer) rs.getObject(6) == null ? null : rs.getInt(6), rs.getString(7),
                        rs.getString(8), rs.getObject(9, OffsetDateTime.class)),
                args.toArray());
    }

    @Transactional
    public void setStatus(long id, String status) {
        if (jdbc.update("UPDATE feedback SET status = ? WHERE id = ?", status, id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Feedback not found.");
        }
    }

    /**
     * CSV for analysis in a spreadsheet. Cells starting with = + - @ are prefixed with ' so a
     * spreadsheet never runs them as formulas (CSV injection: feedback text comes from users).
     */
    public String exportCsv() {
        StringBuilder out = new StringBuilder("id,created_at,role,category,rating,status,page,message\n");
        for (FeedbackView f : list(null, null)) {
            out.append(f.id()).append(',').append(cell(String.valueOf(f.createdAt()))).append(',')
               .append(cell(f.role())).append(',').append(cell(f.category())).append(',')
               .append(f.rating() == null ? "" : f.rating()).append(',').append(cell(f.status())).append(',')
               .append(cell(f.page())).append(',').append(cell(f.message())).append('\n');
        }
        return out.toString();
    }

    static String cell(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------ System Usability Scale

    /**
     * Standard SUS scoring (Brooke, 1996): odd statements are positive (answer - 1),
     * even statements are negative (5 - answer); the sum of 0-4 points times 2.5 gives 0-100.
     */
    static double susScore(List<Integer> answers) {
        if (answers.size() != 10) throw new IllegalArgumentException("SUS needs 10 answers");
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            int a = answers.get(i);
            if (a < 1 || a > 5) throw new IllegalArgumentException("answers must be 1-5");
            sum += (i % 2 == 0) ? a - 1 : 5 - a;
        }
        return sum * 2.5;
    }

    @Transactional
    public SusMine submitSus(AuthenticatedUser u, SusRequest r) {
        double score = susScore(r.answers());
        String array = r.answers().stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        jdbc.update("""
                INSERT INTO sus_responses (user_id, role, answers, score, comment)
                VALUES (?, ?, CAST(? AS smallint[]), ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET answers = EXCLUDED.answers, score = EXCLUDED.score,
                    comment = EXCLUDED.comment, role = EXCLUDED.role, submitted_at = now()""",
                u.id(), u.role().name(), array, score,
                r.comment() == null || r.comment().isBlank() ? null : r.comment().trim());
        return new SusMine(true, score);
    }

    public SusMine mine(AuthenticatedUser u) {
        List<Double> s = jdbc.query("SELECT score FROM sus_responses WHERE user_id = ?", (rs, i) -> rs.getDouble(1), u.id());
        return s.isEmpty() ? new SusMine(false, null) : new SusMine(true, s.get(0));
    }

    public SusSummary susSummary() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM sus_responses", Integer.class);
        int count = n == null ? 0 : n;
        Double avg = count == 0 ? null : jdbc.queryForObject("SELECT round(avg(score), 1) FROM sus_responses", Double.class);
        List<RoleScore> byRole = jdbc.query("""
                SELECT role, count(*), round(avg(score), 1) FROM sus_responses GROUP BY role ORDER BY role""",
                (rs, i) -> new RoleScore(rs.getString(1), rs.getInt(2), rs.getDouble(3)));
        List<Double> perItem = count == 0 ? List.of() : jdbc.query("""
                SELECT i, round(avg(answers[i]), 2) FROM sus_responses, generate_series(1, 10) AS i
                GROUP BY i ORDER BY i""", (rs, k) -> rs.getDouble(2));
        return new SusSummary(count, avg, byRole, perItem);
    }
}

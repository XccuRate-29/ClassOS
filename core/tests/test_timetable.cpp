#include "classos/timetable.hpp"

#include <iostream>
#include <map>
#include <set>
#include <stdexcept>

using namespace classos::timetable;

static int failures = 0;
#define CHECK(cond)                                                                  \
    do {                                                                             \
        if (!(cond)) {                                                               \
            ++failures;                                                              \
            std::cerr << "FAIL " << __FILE__ << ":" << __LINE__ << "  " #cond "\n"; \
        }                                                                            \
    } while (0)

static void expect_valid(const Problem& p, const Solution& s) {
    auto violations = validate(p, s.placements);
    for (auto& v : violations) std::cerr << "  violation: " << v << "\n";
    CHECK(violations.empty());
}

static Problem small_grid() {
    Problem p;
    p.days = 2;
    p.periods_per_day = 4;
    p.break_after = {2};
    p.rooms = {{1, 60, RoomType::Classroom}, {2, 40, RoomType::Lab}};
    return p;
}

static void test_basic_placement_is_valid() {
    Problem p = small_grid();
    // Two sections share teacher 100; each needs two lectures.
    p.events = {{1, 10, 100, 1, 50, EventKind::Lecture, 1}, {2, 10, 100, 1, 50, EventKind::Lecture, 1},
                {3, 11, 100, 2, 50, EventKind::Lecture, 1}, {4, 11, 100, 2, 50, EventKind::Lecture, 1}};
    Solution s = solve(p);
    CHECK(s.complete);
    CHECK(s.placements.size() == 4);
    expect_valid(p, s);
}

static void test_labs_use_lab_rooms_and_never_cross_breaks() {
    Problem p = small_grid();
    p.events = {{1, 10, 100, 1, 35, EventKind::Lab, 2}, {2, 11, 101, 2, 35, EventKind::Lab, 2}};
    Solution s = solve(p);
    CHECK(s.complete);
    for (auto& pl : s.placements) {
        CHECK(pl.room_id == 2);
        CHECK(pl.start_period == 1 || pl.start_period == 3);  // 2-3 would cross the break after 2
    }
    expect_valid(p, s);
}

static void test_spreads_one_course_across_days() {
    Problem p;
    p.days = 5;
    p.periods_per_day = 6;
    p.rooms = {{1, 60, RoomType::Classroom}};
    for (int i = 1; i <= 3; ++i) p.events.push_back({i, 10, 100, 1, 50, EventKind::Lecture, 1});
    Solution s = solve(p);
    CHECK(s.complete);
    std::set<int> days;
    for (auto& pl : s.placements) days.insert(pl.day);
    CHECK(days.size() == 3);
}

static void test_respects_teacher_unavailability_and_fixed_slots() {
    Problem p = small_grid();
    // Teacher 100 unavailable all of day 1; room 1 already used on day 2 period 1.
    for (int k = 1; k <= 4; ++k) p.teacher_unavailable.push_back({100, 1, k});
    p.fixed = {{1, 0, 0, 2, 1, 1}};
    p.events = {{1, 10, 100, 1, 50, EventKind::Lecture, 1}};
    Solution s = solve(p);
    CHECK(s.complete);
    CHECK(s.placements[0].day == 2);
    CHECK(s.placements[0].start_period != 1);
    expect_valid(p, s);
}

static void test_reports_reasons_for_impossible_events() {
    Problem p = small_grid();
    p.events = {
        {1, 10, 100, 1, 80, EventKind::Lab, 2},      // no lab holds 80
        {2, 11, 101, 2, 30, EventKind::Lecture, 3},  // 3 periods never fit between breaks
    };
    Solution s = solve(p);
    CHECK(!s.complete);
    CHECK(s.unplaced.size() == 2);
    std::map<int, std::string> reasons;
    for (auto& u : s.unplaced) reasons[u.event_id] = u.reason;
    CHECK(reasons[1].find("holds 80") != std::string::npos);
    CHECK(reasons[2].find("does not fit between breaks") != std::string::npos);
}

static void test_overloaded_section_gives_partial_result() {
    Problem p = small_grid();  // 2 days x 4 periods = 8 slots for the section
    for (int i = 1; i <= 10; ++i) p.events.push_back({i, 10 + i, 100 + i, 1, 30, EventKind::Lecture, 1});
    Solution s = solve(p);
    CHECK(!s.complete);
    CHECK(s.placements.size() == 8);
    CHECK(s.unplaced.size() == 2);
    expect_valid(p, s);
}

static void test_invalid_input_throws() {
    Problem p = small_grid();
    p.events = {{1, 10, 100, 1, 30, EventKind::Lecture, 1}, {1, 10, 100, 1, 30, EventKind::Lecture, 1}};
    bool threw = false;
    try { solve(p); } catch (const std::invalid_argument&) { threw = true; }
    CHECK(threw);
}

// Roughly one department: 5 sections, 25 offerings, 8 teachers, 10 rooms.
static Problem department_instance() {
    Problem p;
    p.days = 5;
    p.periods_per_day = 8;
    p.break_after = {4};
    for (int r = 1; r <= 6; ++r) p.rooms.push_back({r, 70, RoomType::Classroom});
    p.rooms.push_back({7, 120, RoomType::SeminarHall});
    for (int r = 8; r <= 10; ++r) p.rooms.push_back({r, 65, RoomType::Lab});

    struct Course { int lectures; int lab_blocks; };
    const Course sem3[] = {{3, 1}, {3, 1}, {3, 1}, {4, 0}, {2, 0}};
    const Course sem5[] = {{3, 1}, {3, 0}, {3, 1}, {3, 0}, {2, 1}};
    int id = 0, teacher_rr = 0;
    for (int section = 1; section <= 5; ++section) {
        const Course* courses = section <= 3 ? sem3 : sem5;
        for (int c = 0; c < 5; ++c) {
            std::int64_t offering = section * 10 + c;
            std::int64_t teacher = 100 + (teacher_rr++ % 8);
            for (int l = 0; l < courses[c].lectures; ++l)
                p.events.push_back({++id, offering, teacher, section, 60, EventKind::Lecture, 1});
            for (int b = 0; b < courses[c].lab_blocks; ++b)
                p.events.push_back({++id, offering, teacher, section, 60, EventKind::Lab, 2});
        }
    }
    return p;
}

static void test_department_instance_solves_and_is_deterministic() {
    Problem p = department_instance();
    Solution a = solve(p);
    CHECK(a.complete);
    expect_valid(p, a);
    CHECK(a.stats.elapsed_ms < 5000);

    Solution b = solve(p);
    CHECK(a.placements.size() == b.placements.size());
    bool same = true;
    for (std::size_t i = 0; i < a.placements.size() && i < b.placements.size(); ++i)
        same = same && a.placements[i].day == b.placements[i].day &&
               a.placements[i].start_period == b.placements[i].start_period &&
               a.placements[i].room_id == b.placements[i].room_id;
    CHECK(same);
    std::cout << "department instance: " << p.events.size() << " events, " << a.stats.nodes
              << " nodes, " << a.stats.backtracks << " backtracks, " << a.stats.elapsed_ms << " ms\n";
}

// Forces the "budget exhausted" path: best partial result + greedy completion.
static void test_search_limit_falls_back_to_greedy() {
    Problem p = department_instance();
    p.node_limit = 10;
    Solution s = solve(p);
    CHECK(s.stats.limit_reached);
    CHECK(s.placements.size() + s.unplaced.size() == p.events.size());
    expect_valid(p, s);
    for (auto& u : s.unplaced) CHECK(u.reason.find("search limit") != std::string::npos);
}

// A tight instance where greedy choices fail and backtracking is needed.
// Section 1 must use both periods of the only morning; teacher 200 can only teach then.
static void test_backtracking_recovers_from_bad_first_choice() {
    Problem p;
    p.days = 1;
    p.periods_per_day = 2;
    p.rooms = {{1, 60, RoomType::Classroom}, {2, 60, RoomType::Classroom}};
    p.teacher_unavailable = {{200, 1, 2}};  // teacher 200 only free in period 1
    p.events = {
        {1, 10, 100, 1, 30, EventKind::Lecture, 1},  // section 1, flexible teacher
        {2, 11, 200, 1, 30, EventKind::Lecture, 1},  // section 1, must be period 1
        {3, 12, 100, 2, 30, EventKind::Lecture, 1},  // section 2, same flexible teacher
    };
    Solution s = solve(p);
    CHECK(s.complete);
    expect_valid(p, s);
}

static void test_validator_catches_clashes() {
    Problem p = small_grid();
    p.events = {{1, 10, 100, 1, 30, EventKind::Lecture, 1}, {2, 11, 100, 2, 30, EventKind::Lecture, 1}};
    // Same teacher, same time, same room: should report room and teacher overlaps.
    auto v = validate(p, {{1, 1, 1, 1}, {2, 1, 1, 1}});
    CHECK(v.size() == 2);
}

int main() {
    test_basic_placement_is_valid();
    test_labs_use_lab_rooms_and_never_cross_breaks();
    test_spreads_one_course_across_days();
    test_respects_teacher_unavailability_and_fixed_slots();
    test_reports_reasons_for_impossible_events();
    test_overloaded_section_gives_partial_result();
    test_invalid_input_throws();
    test_department_instance_solves_and_is_deterministic();
    test_search_limit_falls_back_to_greedy();
    test_backtracking_recovers_from_bad_first_choice();
    test_validator_catches_clashes();
    if (failures == 0) std::cout << "All timetable tests passed\n";
    return failures == 0 ? 0 : 1;
}

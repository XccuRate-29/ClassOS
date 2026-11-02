// Offline benchmarks for the evaluation chapter. Deterministic: same output every run on
// the same machine. Times depend on the machine, so record your hardware with the results.
//
//   ./build/classos_bench            (built by CMake alongside the tests)
#include <cmath>
#include <cstdio>
#include <random>

#include "classos/scheduler.hpp"
#include "classos/timetable.hpp"

using namespace classos;

// A department with `sections` sections: odd ones follow the sem-3 pattern, even ones sem-5.
// Rooms scale with size (`roomFactor` < 1 makes them scarce). `teachersPerSection` sets the
// teaching load: 1.6 is light; 0.7-0.8 means each teacher has many courses (the hard case).
static timetable::Problem department(int sections, double roomFactor, double teachersPerSection) {
    using namespace timetable;
    Problem p;
    p.days = 5;
    p.periods_per_day = 8;
    p.break_after = {4};
    p.time_limit = std::chrono::milliseconds(3000);
    int classrooms = std::max(1, static_cast<int>(std::ceil(sections * 1.2 * roomFactor)));
    int labs = std::max(1, static_cast<int>(std::ceil(sections * 0.6 * roomFactor)));
    int teachers = std::max(1, static_cast<int>(std::ceil(sections * teachersPerSection)));
    std::int64_t rid = 1;
    for (int r = 0; r < classrooms; ++r) p.rooms.push_back({rid++, 70, RoomType::Classroom});
    for (int r = 0; r < labs; ++r) p.rooms.push_back({rid++, 65, RoomType::Lab});
    struct C { int lec, labBlocks; };
    const C a[] = {{3, 1}, {3, 1}, {3, 1}, {4, 0}, {2, 0}}, b[] = {{3, 1}, {3, 0}, {3, 1}, {3, 0}, {2, 1}};
    int id = 0, turn = 0;
    for (int s = 1; s <= sections; ++s) {
        const C* cs = s % 2 ? a : b;
        for (int c = 0; c < 5; ++c) {
            std::int64_t off = s * 10 + c, t = 1000 + (turn++ % teachers);
            for (int l = 0; l < cs[c].lec; ++l) p.events.push_back({++id, off, t, s, 60, EventKind::Lecture, 1});
            for (int k = 0; k < cs[c].labBlocks; ++k) p.events.push_back({++id, off, t, s, 60, EventKind::Lab, 2});
        }
    }
    return p;
}

static void timetable_bench() {
    std::printf("Timetable generation: MRV ordering vs input-order baseline (3 s / 2M-node budget)\n");
    std::printf("%-9s %-14s %-6s %-12s %7s %6s %10s %11s %7s %s\n", "sections", "teacher load", "events",
                "ordering", "placed", "valid", "nodes", "backtracks", "ms", "budget");
    for (double load : {1.6, 0.8, 0.7}) {
        for (int sections : {10, 20}) {
            for (auto order : {timetable::VariableOrder::Mrv, timetable::VariableOrder::InputOrder}) {
                timetable::Problem p = department(sections, 0.5, load);
                p.order = order;
                timetable::Solution s = timetable::solve(p);
                bool valid = timetable::validate(p, s.placements).empty();
                char loadLabel[24];
                std::snprintf(loadLabel, sizeof loadLabel, "%.1f t/section", load);
                std::printf("%-9d %-14s %-6zu %-12s %7zu %6s %10lld %11lld %7lld %s\n", sections, loadLabel,
                            p.events.size(), order == timetable::VariableOrder::Mrv ? "MRV" : "input-order",
                            s.placements.size(), valid ? "yes" : "NO", static_cast<long long>(s.stats.nodes),
                            static_cast<long long>(s.stats.backtracks), static_cast<long long>(s.stats.elapsed_ms),
                            s.stats.limit_reached ? "exhausted" : "");
            }
        }
    }
}

// Scenario A, one teacher's backlog: a big low-priority lab report submitted days ago, a
// mid-term assignment, and two later, small, urgent ones that must be returned within hours.
// Minutes relative to "now"; deadlines are grade-by dates.
static std::vector<scheduling::Job> backlog_with_urgent_work() {
    std::vector<scheduling::Job> jobs;
    std::mt19937 rng(7);
    std::int64_t id = 1;
    auto add = [&](std::int64_t queue, int count, double from, double to, double burst, int prio, double deadline) {
        std::uniform_real_distribution<double> when(from, to);
        for (int i = 0; i < count; ++i) jobs.push_back({id++, queue, when(rng), burst, prio, deadline});
    };
    add(1, 60, -3 * 1440, -2 * 1440, 10, 3, 4 * 1440);  // lab report: low priority, due back in 4 days
    add(2, 30, -2 * 1440, -1 * 1440, 15, 2, 3 * 1440);  // mid-term: normal, 3 days
    add(3, 8, -600, -300, 20, 1, 8 * 60);                // quiz: high priority, results needed in 8 hours
    add(4, 5, -240, -60, 5, 2, 12 * 60);                 // short exercise: 12 hours
    return jobs;
}

// Scenario B, two sections submit the same lab within the same hour: the case Round Robin
// is designed for (FCFS grades one whole section before starting the other).
static std::vector<scheduling::Job> two_sections_same_time() {
    std::vector<scheduling::Job> jobs;
    std::mt19937 rng(11);
    std::uniform_real_distribution<double> when(-60, 0);
    std::int64_t id = 1;
    for (int i = 0; i < 40; ++i) jobs.push_back({id++, 1, when(rng) - 30, 12, 2, 3 * 1440});  // section A, slightly earlier
    for (int i = 0; i < 40; ++i) jobs.push_back({id++, 2, when(rng), 12, 2, 3 * 1440});       // section B
    return jobs;
}

static void scheduler_scenario(const char* title, const std::vector<scheduling::Job>& jobs) {
    std::printf("\n%s (%zu submissions; one teacher grading continuously from now)\n", title, jobs.size());
    std::printf("%-26s %10s %10s %13s %9s\n", "policy", "avg wait h", "max wait h", "past grade-by", "fairness");
    auto row = [&](const char* label, scheduling::Policy pol, scheduling::Params p) {
        auto s = scheduling::run(pol, jobs, p);
        std::printf("%-26s %10.1f %10.1f %13d %9.3f\n", label, s.metrics.avg_wait / 60, s.metrics.max_wait / 60,
                    s.metrics.deadline_misses, s.metrics.fairness);
    };
    scheduling::Params p;
    row("FCFS", scheduling::Policy::FCFS, p);
    for (double aging : {0.0, 0.25, 1.0}) {
        p = {};
        p.aging_per_hour = aging;
        char label[40];
        std::snprintf(label, sizeof label, "Priority, aging %.2f/h", aging);
        row(label, scheduling::Policy::Priority, p);
    }
    for (int q : {1, 3, 10}) {
        p = {};
        p.quantum = q;
        char label[40];
        std::snprintf(label, sizeof label, "Round Robin, quantum %d", q);
        row(label, scheduling::Policy::RoundRobin, p);
    }
}

static void scheduler_bench() {
    scheduler_scenario("Grading scenario A: backlog plus urgent work", backlog_with_urgent_work());
    scheduler_scenario("Grading scenario B: two sections submit at the same time", two_sections_same_time());
}

int main() {
    timetable_bench();
    scheduler_bench();
    return 0;
}

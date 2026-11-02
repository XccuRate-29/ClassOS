#include "classos/scheduler.hpp"

#include <cmath>
#include <iostream>
#include <map>
#include <random>
#include <set>
#include <stdexcept>

using namespace classos::scheduling;

static int failures = 0;
#define CHECK(cond)                                                                  \
    do {                                                                             \
        if (!(cond)) {                                                               \
            ++failures;                                                              \
            std::cerr << "FAIL " << __FILE__ << ":" << __LINE__ << "  " #cond "\n"; \
        }                                                                            \
    } while (0)

static bool near(double a, double b) { return std::fabs(a - b) < 1e-6; }

static std::vector<std::int64_t> ids(const Schedule& s) {
    std::vector<std::int64_t> out;
    for (auto& slot : s.order) out.push_back(slot.job_id);
    return out;
}

// Independent checks every schedule must pass, whatever the policy.
static void expect_valid(const std::vector<Job>& jobs, const Schedule& s) {
    CHECK(s.order.size() == jobs.size());
    std::map<std::int64_t, const Job*> byId;
    for (auto& j : jobs) byId[j.id] = &j;
    std::set<std::int64_t> seen;
    double prevFinish = -1e18;
    for (auto& slot : s.order) {
        CHECK(seen.insert(slot.job_id).second);                       // each job exactly once
        CHECK(slot.start >= byId[slot.job_id]->arrival - 1e-9);       // never before it arrived
        CHECK(slot.start >= prevFinish - 1e-9);                       // one at a time, non-preemptive
        CHECK(near(slot.finish - slot.start, byId[slot.job_id]->burst));
        prevFinish = slot.finish;
    }
}

// Silberschatz's FCFS example: bursts 24, 3, 3 arriving together -> waits 0, 24, 27, average 17.
static void test_fcfs_textbook_example() {
    std::vector<Job> jobs = {{1, 1, 0, 24, 2, 1000}, {2, 1, 0, 3, 2, 1000}, {3, 1, 0, 3, 2, 1000}};
    Schedule s = run(Policy::FCFS, jobs, {});
    CHECK((ids(s) == std::vector<std::int64_t>{1, 2, 3}));
    CHECK(near(s.metrics.avg_wait, 17.0));
    CHECK(near(s.metrics.max_wait, 27.0));
    CHECK(near(s.metrics.avg_turnaround, 27.0));
    expect_valid(jobs, s);
}

static void test_priority_orders_by_class_without_aging() {
    Params p;
    p.aging_per_hour = 0;
    std::vector<Job> jobs = {{1, 1, 0, 10, 3, 1e9}, {2, 2, 0, 10, 1, 1e9}, {3, 3, 0, 10, 2, 1e9}};
    CHECK((ids(run(Policy::Priority, jobs, p)) == std::vector<std::int64_t>{2, 3, 1}));
}

// Starvation: a low-priority submission from 3 days ago, behind a steady stream of
// high-priority ones. Without aging it is graded last; with aging it moves up.
static void test_aging_prevents_starvation() {
    std::vector<Job> jobs = {{100, 9, -3 * 24 * 60, 10, 3, 1e9}};
    for (int k = 0; k < 30; ++k) jobs.push_back({k + 1, 1, -60.0 + k, 10, 1, 1e9});

    Params noAging;
    noAging.aging_per_hour = 0;
    Schedule starved = run(Policy::Priority, jobs, noAging);
    CHECK(starved.order.back().job_id == 100);

    Params aging;  // 0.5 per hour: 72 h waited adds 36, more than the 20-point gap to the high class
    aging.aging_per_hour = 0.5;
    Schedule fair = run(Policy::Priority, jobs, aging);
    CHECK(fair.order.front().job_id == 100);
    CHECK(fair.metrics.max_wait < starved.metrics.max_wait);
    expect_valid(jobs, fair);
}

// Round Robin across assignments: a big backlog no longer delays a small assignment.
static void test_round_robin_lets_a_small_assignment_through() {
    std::vector<Job> jobs;
    for (int k = 1; k <= 5; ++k) jobs.push_back({k, 1, -100.0 + k, 10, 2, 1e9});  // assignment A, submitted first
    jobs.push_back({6, 2, -50, 10, 2, 1e9});                                        // assignment B, one submission
    Params p;
    p.quantum = 2;
    Schedule rr = run(Policy::RoundRobin, jobs, p);
    Schedule fcfs = run(Policy::FCFS, jobs, p);
    CHECK((ids(rr) == std::vector<std::int64_t>{1, 2, 6, 3, 4, 5}));
    CHECK(fcfs.order.back().job_id == 6);
    auto waitOf = [](const Schedule& s, std::int64_t id) {
        for (auto& slot : s.order) if (slot.job_id == id) return slot.start;
        return -1.0;
    };
    CHECK(waitOf(rr, 6) < waitOf(fcfs, 6));
    expect_valid(jobs, rr);
}

// Two classes submit at the same time: FCFS grades one whole class before the other;
// Round Robin shares the grader, so both classes wait about equally (higher Jain index).
static void test_round_robin_is_fairer_between_competing_assignments() {
    std::vector<Job> jobs;
    for (int k = 1; k <= 5; ++k) jobs.push_back({k, 1, -101.0 + k, 10, 2, 1e9});      // A arrives just before B
    for (int k = 1; k <= 5; ++k) jobs.push_back({10 + k, 2, -96.0 + k, 10, 2, 1e9});
    Params p;
    p.quantum = 2;
    Schedule rr = run(Policy::RoundRobin, jobs, p), fcfs = run(Policy::FCFS, jobs, p);
    CHECK(rr.metrics.fairness > fcfs.metrics.fairness);
    CHECK(rr.metrics.fairness > 0.99);
    expect_valid(jobs, rr);
}

static void test_idle_until_next_arrival() {
    std::vector<Job> jobs = {{1, 1, 0, 5, 2, 1e9}, {2, 1, 100, 5, 2, 1e9}};
    for (Policy pol : {Policy::FCFS, Policy::Priority, Policy::RoundRobin}) {
        Schedule s = run(pol, jobs, {});
        CHECK(near(s.order[1].start, 100));  // grader waits for the second submission
        CHECK(near(s.metrics.avg_wait, 0));
    }
}

static void test_deadline_misses_and_fairness_bounds() {
    std::vector<Job> jobs = {{1, 1, 0, 30, 2, 20}, {2, 2, 0, 30, 2, 100}};
    Schedule s = run(Policy::FCFS, jobs, {});
    CHECK(s.metrics.deadline_misses == 1);  // job 1 finishes at 30 > 20
    CHECK(s.metrics.fairness > 0 && s.metrics.fairness <= 1.0 + 1e-9);

    std::vector<Job> equal = {{1, 1, 0, 10, 2, 1e9}, {2, 2, 0, 10, 2, 1e9}};
    Params p;
    p.start = 1000;  // both wait 1000 before grading starts
    CHECK(run(Policy::RoundRobin, equal, p).metrics.fairness > 0.99);
}

static void test_empty_and_invalid_input() {
    CHECK(run(Policy::FCFS, {}, {}).order.empty());
    auto throws = [](std::vector<Job> jobs, Params p) {
        try { run(Policy::RoundRobin, jobs, p); } catch (const std::invalid_argument&) { return true; }
        return false;
    };
    CHECK(throws({{1, 1, 0, 0, 2, 0}}, {}));                         // zero burst
    CHECK(throws({{1, 1, 0, 5, 4, 0}}, {}));                         // bad priority
    CHECK(throws({{1, 1, 0, 5, 2, 0}, {1, 2, 0, 5, 2, 0}}, {}));     // duplicate id
    Params bad;
    bad.quantum = 0;
    CHECK(throws({{1, 1, 0, 5, 2, 0}}, bad));
}

// Random queues: every policy must produce a valid, deterministic schedule.
static void test_random_queues_are_always_valid() {
    std::mt19937 rng(42);
    std::uniform_real_distribution<double> arrival(-5000, 500), burst(2, 30);
    std::uniform_int_distribution<int> prio(1, 3), queue(1, 6);
    for (int round = 0; round < 50; ++round) {
        std::vector<Job> jobs;
        for (int k = 0; k < 80; ++k) jobs.push_back({k, queue(rng), arrival(rng), burst(rng), prio(rng), arrival(rng) + 3000});
        for (Policy pol : {Policy::FCFS, Policy::Priority, Policy::RoundRobin}) {
            Schedule a = run(pol, jobs, {}), b = run(pol, jobs, {});
            expect_valid(jobs, a);
            CHECK(ids(a) == ids(b));
        }
    }
}

int main() {
    test_fcfs_textbook_example();
    test_priority_orders_by_class_without_aging();
    test_aging_prevents_starvation();
    test_round_robin_lets_a_small_assignment_through();
    test_round_robin_is_fairer_between_competing_assignments();
    test_idle_until_next_arrival();
    test_deadline_misses_and_fairness_bounds();
    test_empty_and_invalid_input();
    test_random_queues_are_always_valid();
    if (failures == 0) std::cout << "All scheduler tests passed\n";
    return failures == 0 ? 0 : 1;
}

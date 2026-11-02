#pragma once
// Scheduling the assignment evaluation queue with CPU-scheduling algorithms.
//
// Mapping
//   job          one submission waiting to be graded
//   arrival      when it was submitted (minutes, relative to "now"; negative = in the past)
//   burst        the teacher's estimate of minutes to grade one submission of that assignment
//   queue        the assignment it belongs to
//   priority     1 = high, 2 = normal, 3 = low (set per assignment)
//   deadline     the date grades are due (minutes, relative to "now")
//
// All policies are NON-PREEMPTIVE: grading one submission is not paused halfway.
//   FCFS         earliest submission first
//   Priority     highest effective priority first, where
//                  effective = (4 - priority) * priority_step + aging_per_hour * hours waited
//                so a low-priority submission gains priority while it waits and cannot starve
//   RoundRobin   rotate between assignments, taking up to `quantum` submissions from each
//                in turn (FCFS inside an assignment), so one large assignment cannot hold
//                up every other class
//
// run() simulates grading one job after another from `start` and reports metrics, so
// the policies can be compared on the same queue.

#include <cstdint>
#include <vector>

namespace classos::scheduling {

enum class Policy { FCFS, Priority, RoundRobin };

struct Job {
    std::int64_t id;
    std::int64_t queue;
    double arrival;
    double burst;
    int priority;      // 1..3
    double deadline;
};

struct Params {
    double aging_per_hour = 0.25;
    double priority_step = 10.0;
    int quantum = 3;
    double start = 0.0;  // when the teacher starts grading
};

struct Slot {
    std::int64_t job_id;
    double start;
    double finish;
};

struct Metrics {
    int jobs = 0;
    double avg_wait = 0;        // start - arrival
    double max_wait = 0;
    double avg_turnaround = 0;  // finish - arrival
    int deadline_misses = 0;    // finish > deadline
    double fairness = 1.0;      // Jain's index over each queue's average wait (1 = every class waits equally)
};

struct Schedule {
    std::vector<Slot> order;
    Metrics metrics;
};

// Throws std::invalid_argument for malformed input.
Schedule run(Policy policy, const std::vector<Job>& jobs, const Params& params);

const char* name(Policy policy);

}  // namespace classos::scheduling

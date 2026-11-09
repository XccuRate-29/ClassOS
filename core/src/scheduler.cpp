#include "classos/scheduler.hpp"

#include <algorithm>
#include <cmath>
#include <deque>
#include <limits>
#include <map>
#include <set>
#include <stdexcept>

namespace classos {
namespace scheduling {

const char* name(Policy policy) {
    switch (policy) {
        case Policy::FCFS: return "FCFS";
        case Policy::Priority: return "PRIORITY";
        case Policy::RoundRobin: return "ROUND_ROBIN";
    }
    return "?";
}

namespace {

void validate(const std::vector<Job>& jobs, const Params& p) {
    if (p.quantum < 1) throw std::invalid_argument("quantum must be at least 1");
    if (!(p.aging_per_hour >= 0) || !std::isfinite(p.aging_per_hour)) throw std::invalid_argument("aging must be >= 0");
    if (!(p.priority_step > 0) || !std::isfinite(p.priority_step)) throw std::invalid_argument("priority step must be > 0");
    if (!std::isfinite(p.start)) throw std::invalid_argument("start must be finite");
    std::set<std::int64_t> ids;
    for (const Job& j : jobs) {
        if (!ids.insert(j.id).second) throw std::invalid_argument("duplicate job id " + std::to_string(j.id));
        if (!(j.burst > 0) || !std::isfinite(j.burst)) throw std::invalid_argument("burst must be > 0");
        if (j.priority < 1 || j.priority > 3) throw std::invalid_argument("priority must be 1, 2 or 3");
        if (!std::isfinite(j.arrival) || !std::isfinite(j.deadline)) throw std::invalid_argument("times must be finite");
    }
}

Metrics measure(const std::vector<Job>& jobs, const std::vector<Slot>& order) {
    Metrics m;
    m.jobs = static_cast<int>(order.size());
    if (order.empty()) return m;
    std::map<std::int64_t, const Job*> byId;
    for (const Job& j : jobs) byId[j.id] = &j;

    std::map<std::int64_t, std::pair<double, int>> perQueue;  // queue -> (sum of waits, count)
    double waitSum = 0, turnSum = 0;
    for (const Slot& s : order) {
        const Job& j = *byId.at(s.job_id);
        double wait = s.start - j.arrival, turn = s.finish - j.arrival;
        waitSum += wait;
        turnSum += turn;
        m.max_wait = std::max(m.max_wait, wait);
        if (s.finish > j.deadline + 1e-9) ++m.deadline_misses;
        auto& q = perQueue[j.queue];
        q.first += wait;
        q.second += 1;
    }
    m.avg_wait = waitSum / order.size();
    m.avg_turnaround = turnSum / order.size();

    // Jain's fairness index over each assignment's average wait: (sum x)^2 / (n * sum x^2).
    // 1 = every class waits equally long for its grades.
    double sum = 0, sumSq = 0;
    std::map<std::int64_t, std::pair<double, int> >::iterator it = perQueue.begin();
    for (; it != perQueue.end(); ++it) {
        const double avg = it->second.first / static_cast<double>(it->second.second);
        sum += avg;
        sumSq += avg * avg;
    }
    m.fairness = sumSq == 0 ? 1.0 : (sum * sum) / (perQueue.size() * sumSq);
    return m;
}

}  // namespace

Schedule run(Policy policy, const std::vector<Job>& jobs, const Params& p) {
    validate(jobs, p);
    const int n = static_cast<int>(jobs.size());

    // Arrival order, ties broken by id: the basis for FCFS and for order inside a queue.
    std::vector<int> byArrival(n);
    for (int i = 0; i < n; ++i) byArrival[i] = i;
    std::sort(byArrival.begin(), byArrival.end(), [&](int a, int b) {
        if (jobs[a].arrival != jobs[b].arrival) return jobs[a].arrival < jobs[b].arrival;
        return jobs[a].id < jobs[b].id;
    });

    Schedule s;
    double t = p.start;
    std::vector<bool> done(n, false);
    // Non-preemptive: once started, a job runs to completion. If nothing has arrived, the grader is idle.
    auto grade = [&](int i) {
        double start = std::max(t, jobs[i].arrival);
        s.order.push_back({jobs[i].id, start, start + jobs[i].burst});
        t = start + jobs[i].burst;
        done[i] = true;
    };

    switch (policy) {
        case Policy::FCFS:
            for (int i : byArrival) grade(i);
            break;

        case Policy::Priority:
            for (int step = 0; step < n; ++step) {
                // Only jobs that have arrived are eligible; if none has, jump to the next arrival.
                double earliest = std::numeric_limits<double>::infinity();
                for (int i = 0; i < n; ++i) if (!done[i]) earliest = std::min(earliest, jobs[i].arrival);
                if (earliest > t) t = earliest;

                int best = -1;
                double bestScore = -std::numeric_limits<double>::infinity();
                for (int i : byArrival) {  // arrival order makes ties go to the earlier submission
                    if (done[i] || jobs[i].arrival > t) continue;
                    double hoursWaited = (t - jobs[i].arrival) / 60.0;
                    double score = (4 - jobs[i].priority) * p.priority_step + p.aging_per_hour * hoursWaited;
                    if (score > bestScore) {
                        bestScore = score;
                        best = i;
                    }
                }
                grade(best);
            }
            break;

        case Policy::RoundRobin: {
            // One FIFO per assignment, visited in order of each assignment's first submission.
            std::vector<std::int64_t> queueOrder;
            std::map<std::int64_t, std::deque<int>> queues;
            for (int i : byArrival) {
                if (!queues.count(jobs[i].queue)) queueOrder.push_back(jobs[i].queue);
                queues[jobs[i].queue].push_back(i);
            }
            int remaining = n;
            while (remaining > 0) {
                bool progressed = false;
                for (std::int64_t q : queueOrder) {
                    auto& fifo = queues[q];
                    for (int taken = 0; taken < p.quantum && !fifo.empty() && jobs[fifo.front()].arrival <= t; ++taken) {
                        grade(fifo.front());
                        fifo.pop_front();
                        --remaining;
                        progressed = true;
                    }
                }
                if (!progressed) {  // nothing has arrived yet: idle until the next submission
                    double next = std::numeric_limits<double>::infinity();
                    std::map<std::int64_t, std::deque<int> >::iterator it = queues.begin();
                    for (; it != queues.end(); ++it) {
                        if (!it->second.empty()) next = std::min(next, jobs[it->second.front()].arrival);
                    }
                    t = next;
                }
            }
            break;
        }
    }

    s.metrics = measure(jobs, s.order);
    return s;
}

}  // namespace scheduling
}  // namespace classos

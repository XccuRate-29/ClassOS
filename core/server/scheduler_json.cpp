#include "scheduler_json.hpp"

#include <algorithm>
#include <stdexcept>

#include "classos/scheduler.hpp"

namespace classos::scheduling {

namespace {

Policy policy_of(const std::string& s) {
    if (s == "FCFS") return Policy::FCFS;
    if (s == "PRIORITY") return Policy::Priority;
    if (s == "ROUND_ROBIN") return Policy::RoundRobin;
    throw std::invalid_argument("unknown policy: " + s);
}

}  // namespace

nlohmann::json schedule_from_json(const nlohmann::json& j) {
    Params p;
    if (j.contains("params")) {
        const auto& pj = j.at("params");
        p.aging_per_hour = pj.value("agingPerHour", p.aging_per_hour);
        p.priority_step = pj.value("priorityStep", p.priority_step);
        p.quantum = std::clamp(pj.value("quantum", p.quantum), 1, 100);
        p.start = pj.value("start", p.start);
    }
    std::vector<Job> jobs;
    const auto& list = j.at("jobs");
    if (list.size() > 20000) throw std::invalid_argument("too many jobs");
    for (const auto& x : list)
        jobs.push_back({x.at("id").get<std::int64_t>(), x.at("queue").get<std::int64_t>(),
                        x.at("arrival").get<double>(), x.at("burst").get<double>(),
                        x.at("priority").get<int>(), x.at("deadline").get<double>()});

    nlohmann::json results = nlohmann::json::array();
    for (const auto& name : j.at("policies")) {
        Schedule s = run(policy_of(name.get<std::string>()), jobs, p);
        nlohmann::json order = nlohmann::json::array();
        for (const auto& slot : s.order)
            order.push_back({{"jobId", slot.job_id}, {"start", slot.start}, {"finish", slot.finish}});
        const Metrics& m = s.metrics;
        results.push_back({{"policy", name},
                           {"order", order},
                           {"metrics", {{"jobs", m.jobs},
                                        {"avgWait", m.avg_wait},
                                        {"maxWait", m.max_wait},
                                        {"avgTurnaround", m.avg_turnaround},
                                        {"deadlineMisses", m.deadline_misses},
                                        {"fairness", m.fairness}}}});
    }
    return nlohmann::json{{"results", results}};
}

}  // namespace classos::scheduling

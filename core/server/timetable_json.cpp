#include "timetable_json.hpp"

#include <algorithm>
#include <stdexcept>

namespace classos::timetable {

namespace {

RoomType room_type(const std::string& s) {
    if (s == "CLASSROOM") return RoomType::Classroom;
    if (s == "LAB") return RoomType::Lab;
    if (s == "SEMINAR_HALL") return RoomType::SeminarHall;
    throw std::invalid_argument("unknown room type: " + s);
}

EventKind event_kind(const std::string& s) {
    if (s == "LECTURE") return EventKind::Lecture;
    if (s == "LAB") return EventKind::Lab;
    throw std::invalid_argument("unknown event kind: " + s);
}

}  // namespace

Problem problem_from_json(const nlohmann::json& j) {
    Problem p;
    p.days = j.at("days").get<int>();
    p.periods_per_day = j.at("periodsPerDay").get<int>();
    p.break_after = j.value("breakAfter", std::vector<int>{});
    // Callers cannot ask for unbounded work.
    p.time_limit = std::chrono::milliseconds(std::clamp(j.value("timeLimitMs", 5000), 100, 30000));
    p.node_limit = std::clamp<std::int64_t>(j.value("nodeLimit", std::int64_t{2'000'000}), 1, 20'000'000);
    const std::string order = j.value("variableOrder", std::string("MRV"));
    if (order == "INPUT") p.order = VariableOrder::InputOrder;
    else if (order != "MRV") throw std::invalid_argument("variableOrder must be MRV or INPUT");

    for (const auto& r : j.at("rooms"))
        p.rooms.push_back({r.at("id").get<std::int64_t>(), r.at("capacity").get<int>(),
                           room_type(r.at("type").get<std::string>())});

    for (const auto& e : j.at("events"))
        p.events.push_back({e.at("id").get<int>(), e.at("offeringId").get<std::int64_t>(),
                            e.at("teacherId").get<std::int64_t>(), e.at("sectionId").get<std::int64_t>(),
                            e.at("size").get<int>(), event_kind(e.at("kind").get<std::string>()),
                            e.at("length").get<int>()});

    if (j.contains("fixed"))
        for (const auto& f : j.at("fixed"))
            p.fixed.push_back({f.value("roomId", std::int64_t{0}), f.value("teacherId", std::int64_t{0}),
                               f.value("sectionId", std::int64_t{0}), f.at("day").get<int>(),
                               f.at("startPeriod").get<int>(), f.at("length").get<int>()});

    if (j.contains("teacherUnavailable"))
        for (const auto& u : j.at("teacherUnavailable"))
            p.teacher_unavailable.push_back({u.at("teacherId").get<std::int64_t>(), u.at("day").get<int>(),
                                             u.at("period").get<int>()});
    return p;
}

nlohmann::json solution_to_json(const Problem& problem, const Solution& s) {
    nlohmann::json placements = nlohmann::json::array();
    for (const auto& pl : s.placements)
        placements.push_back({{"eventId", pl.event_id}, {"day", pl.day},
                              {"startPeriod", pl.start_period}, {"roomId", pl.room_id}});

    nlohmann::json unplaced = nlohmann::json::array();
    for (const auto& u : s.unplaced) unplaced.push_back({{"eventId", u.event_id}, {"reason", u.reason}});

    return nlohmann::json{
        {"complete", s.complete},
        {"placements", placements},
        {"unplaced", unplaced},
        // Independent re-check of the result; should always be empty.
        {"violations", validate(problem, s.placements)},
        {"stats", {{"nodes", s.stats.nodes},
                   {"backtracks", s.stats.backtracks},
                   {"elapsedMs", s.stats.elapsed_ms},
                   {"limitReached", s.stats.limit_reached}}},
    };
}

}  // namespace classos::timetable

#pragma once
// JSON <-> solver structs. Field names match the Java client (camelCase).
// The protocol is documented in docs/solver-protocol.md.
#include <nlohmann/json.hpp>

#include "classos/timetable.hpp"

namespace classos::timetable {

Problem problem_from_json(const nlohmann::json& j);
nlohmann::json solution_to_json(const Problem& problem, const Solution& solution);

}  // namespace classos::timetable

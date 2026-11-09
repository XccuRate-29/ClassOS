#pragma once
// JSON for POST /v1/schedule. Protocol in docs/solver-protocol.md.
#include <nlohmann/json.hpp>

namespace classos {
namespace scheduling {

/** Runs every requested policy on the same jobs and returns one result per policy. */
nlohmann::json schedule_from_json(const nlohmann::json& request);

}  // namespace scheduling
}  // namespace classos

// ClassOS solver service: a small HTTP wrapper around the timetable library.
// Has no authentication of its own, so it must only listen on localhost or a
// private Docker network that only the Spring Boot backend can reach.
#include <httplib.h>

#include <cstdlib>
#include <iostream>
#include <string>

#include "scheduler_json.hpp"
#include "timetable_json.hpp"

namespace {

std::string error_body(const std::string& message) {
    return nlohmann::json{{"error", message}}.dump();
}

}  // namespace

int main() {
    const char* host_env = std::getenv("SOLVER_HOST");
    const char* port_env = std::getenv("SOLVER_PORT");
    const std::string host = host_env ? host_env : "127.0.0.1";
    const int port = port_env ? std::atoi(port_env) : 7070;

    httplib::Server server;
    server.set_payload_max_length(5 * 1024 * 1024);

    server.Get("/health", [](const httplib::Request&, httplib::Response& res) {
        res.set_content(R"({"status":"UP"})", "application/json");
    });

    // httplib serves requests on a thread pool. solve() keeps all state in local
    // objects, so concurrent requests are independent and need no locking.
    server.Post("/v1/timetable/solve", [](const httplib::Request& req, httplib::Response& res) {
        try {
            const auto input = nlohmann::json::parse(req.body);
            const auto problem = classos::timetable::problem_from_json(input);
            const auto solution = classos::timetable::solve(problem);
            res.set_content(classos::timetable::solution_to_json(problem, solution).dump(), "application/json");
        } catch (const nlohmann::json::exception& e) {
            res.status = 400;
            res.set_content(error_body(std::string("invalid JSON: ") + e.what()), "application/json");
        } catch (const std::invalid_argument& e) {
            res.status = 400;
            res.set_content(error_body(e.what()), "application/json");
        } catch (const std::exception& e) {
            res.status = 500;
            res.set_content(error_body(e.what()), "application/json");
        }
    });

    // Grading queue: runs FCFS / PRIORITY / ROUND_ROBIN on the same submissions.
    server.Post("/v1/schedule", [](const httplib::Request& req, httplib::Response& res) {
        try {
            const auto input = nlohmann::json::parse(req.body);
            res.set_content(classos::scheduling::schedule_from_json(input).dump(), "application/json");
        } catch (const nlohmann::json::exception& e) {
            res.status = 400;
            res.set_content(error_body(std::string("invalid JSON: ") + e.what()), "application/json");
        } catch (const std::invalid_argument& e) {
            res.status = 400;
            res.set_content(error_body(e.what()), "application/json");
        } catch (const std::exception& e) {
            res.status = 500;
            res.set_content(error_body(e.what()), "application/json");
        }
    });

    std::cout << "ClassOS solver listening on " << host << ":" << port << std::endl;
    if (!server.listen(host, port)) {
        std::cerr << "Could not listen on " << host << ":" << port << " (port in use?)" << std::endl;
        return 1;
    }
    return 0;
}

#pragma once
// Timetable generation as a constraint satisfaction problem (CSP).
//
// Model
//   variable : one Event (a lecture of 1 period, or a lab block of 2 periods)
//   value    : (day, start period, room)
//   hard     : no two events overlap in the same room, for the same teacher, or for
//              the same section; room type and capacity fit; a block never crosses
//              a break; the teacher is available; pre-existing (fixed) slots respected
//   soft     : the same offering is spread across different days; smallest room that fits
//
// Why this is graph colouring: build a conflict graph with an edge between any two
// events sharing a teacher or a section. Time slots are the "colours"; adjacent
// events may not get the same colour. Rooms add a second resource on top of that.
//
// Search: backtracking with
//   - MRV (minimum remaining values): next, place the event with the fewest
//     currently feasible positions, the DSatur idea applied to a CSP
//   - degree tie-break: among equals, the event with most unplaced neighbours
//   - value ordering by soft-constraint score
// If the node/time budget runs out, the best partial assignment found is completed
// greedily and the events still left are reported with a reason.

#include <chrono>
#include <cstdint>
#include <string>
#include <vector>

namespace classos::timetable {

enum class RoomType { Classroom, Lab, SeminarHall };
enum class EventKind { Lecture, Lab };

// Mrv: most constrained event first (the default). InputOrder: events in the order given,
// with the same backtracking; exists only as a baseline to measure what MRV contributes.
enum class VariableOrder { Mrv, InputOrder };

struct Room {
    std::int64_t id;
    int capacity;
    RoomType type;
};

struct Event {
    int id;                    // caller's id, echoed back in results
    std::int64_t offering_id;  // used for the "spread across days" soft rule
    std::int64_t teacher_id;
    std::int64_t section_id;
    int size;                  // students attending
    EventKind kind;
    int length;                // consecutive periods (lab blocks are usually 2)
};

// Already-scheduled occupancy that the solver must work around (manual slots).
// An id of 0 means "does not occupy this kind of resource".
struct Busy {
    std::int64_t room_id;
    std::int64_t teacher_id;
    std::int64_t section_id;
    int day;           // 1-based
    int start_period;  // 1-based
    int length;
};

struct Unavailable {
    std::int64_t teacher_id;
    int day;
    int period;
};

struct Problem {
    int days = 5;
    int periods_per_day = 8;
    std::vector<int> break_after;  // e.g. {4}: lunch between periods 4 and 5
    std::vector<Room> rooms;
    std::vector<Event> events;
    std::vector<Busy> fixed;
    std::vector<Unavailable> teacher_unavailable;
    std::chrono::milliseconds time_limit{5000};
    std::int64_t node_limit = 2'000'000;
    VariableOrder order = VariableOrder::Mrv;
};

struct Placement {
    int event_id;
    int day;
    int start_period;
    std::int64_t room_id;
};

struct Unplaced {
    int event_id;
    std::string reason;
};

struct Stats {
    std::int64_t nodes = 0;
    std::int64_t backtracks = 0;
    std::int64_t elapsed_ms = 0;
    bool limit_reached = false;
};

struct Solution {
    bool complete = false;
    std::vector<Placement> placements;
    std::vector<Unplaced> unplaced;
    Stats stats;
};

// Throws std::invalid_argument for malformed input (unknown room type, length 0, ...).
Solution solve(const Problem& problem);

// Independent checker used by tests and by the server before replying.
// Returns human-readable violations; empty means the placements are valid.
std::vector<std::string> validate(const Problem& problem, const std::vector<Placement>& placements);

}  // namespace classos::timetable

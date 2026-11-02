#include "classos/timetable.hpp"

#include <algorithm>
#include <climits>
#include <map>
#include <set>
#include <stdexcept>
#include <tuple>
#include <unordered_map>

namespace classos::timetable {

namespace {

bool room_fits_kind(RoomType room, EventKind kind) {
    return kind == EventKind::Lab ? room == RoomType::Lab : room != RoomType::Lab;
}

const char* kind_room_name(EventKind kind) {
    return kind == EventKind::Lab ? "lab" : "lecture room (classroom or seminar hall)";
}

struct Candidate {
    int day;    // 0-based
    int start;  // 0-based period
    int room;   // index into problem.rooms
};

class Solver {
public:
    explicit Solver(const Problem& p) : p_(p), D_(p.days), P_(p.periods_per_day) {
        validate_input();
        index_resources();
        apply_fixed();
        build_domains();
    }

    Solution run() {
        start_ = std::chrono::steady_clock::now();
        assigned_.assign(E_, -1);

        int searchable = 0;
        for (int e = 0; e < E_; ++e) searchable += domain_[e].empty() ? 0 : 1;
        searchable_ = searchable;

        bool complete = search(0);
        if (!complete) {
            // State is back to "fixed only" after the search unwinds.
            for (int e = 0; e < E_; ++e) {
                if (best_assigned_.empty()) break;
                if (best_assigned_[e] >= 0) assign(e, best_assigned_[e]);
            }
            greedy_complete();
        }

        Solution sol;
        for (int e = 0; e < E_; ++e) {
            const Event& ev = p_.events[e];
            if (assigned_[e] >= 0) {
                const Candidate& c = domain_[e][assigned_[e]];
                sol.placements.push_back({ev.id, c.day + 1, c.start + 1, p_.rooms[c.room].id});
            } else {
                sol.unplaced.push_back({ev.id, reason_[e].empty() ? dynamic_reason(e) : reason_[e]});
            }
        }
        sol.complete = sol.unplaced.empty();
        stats_.elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::steady_clock::now() - start_).count();
        sol.stats = stats_;
        return sol;
    }

private:
    // ---------------------------------------------------------------- setup
    void validate_input() {
        if (D_ < 1 || D_ > 7) throw std::invalid_argument("days must be between 1 and 7");
        if (P_ < 1 || P_ > 24) throw std::invalid_argument("periods_per_day must be between 1 and 24");
        break_after_.assign(P_, 0);
        for (int b : p_.break_after) {
            if (b < 1 || b >= P_) throw std::invalid_argument("break_after values must be between 1 and periods_per_day - 1");
            break_after_[b - 1] = 1;
        }
        std::set<int> ids;
        for (const Event& e : p_.events) {
            if (!ids.insert(e.id).second) throw std::invalid_argument("duplicate event id " + std::to_string(e.id));
            if (e.length < 1 || e.length > P_) throw std::invalid_argument("event " + std::to_string(e.id) + " has invalid length");
            if (e.size < 0) throw std::invalid_argument("event " + std::to_string(e.id) + " has negative size");
        }
        for (const Room& r : p_.rooms)
            if (r.capacity < 0) throw std::invalid_argument("room capacity cannot be negative");
        for (const Busy& b : p_.fixed)
            if (b.day < 1 || b.day > D_ || b.start_period < 1 || b.length < 1 || b.start_period + b.length - 1 > P_)
                throw std::invalid_argument("fixed slot outside the timetable grid");
        for (const Unavailable& u : p_.teacher_unavailable)
            if (u.day < 1 || u.day > D_ || u.period < 1 || u.period > P_)
                throw std::invalid_argument("teacher unavailability outside the timetable grid");
    }

    void index_resources() {
        E_ = static_cast<int>(p_.events.size());
        R_ = static_cast<int>(p_.rooms.size());
        for (int r = 0; r < R_; ++r) room_index_[p_.rooms[r].id] = r;

        auto idx = [](std::unordered_map<std::int64_t, int>& m, std::int64_t id) {
            auto it = m.find(id);
            if (it != m.end()) return it->second;
            int n = static_cast<int>(m.size());
            m.emplace(id, n);
            return n;
        };
        std::unordered_map<std::int64_t, int> offerings;
        for (const Event& e : p_.events) {
            teacher_of_.push_back(idx(teacher_index_, e.teacher_id));
            section_of_.push_back(idx(section_index_, e.section_id));
            offering_of_.push_back(idx(offerings, e.offering_id));
        }
        const int S = D_ * P_;
        room_busy_.assign(R_, std::vector<int>(S, 0));
        teacher_busy_.assign(teacher_index_.size(), std::vector<int>(S, 0));
        section_busy_.assign(section_index_.size(), std::vector<int>(S, 0));
        teacher_unavailable_.assign(teacher_index_.size(), std::vector<char>(S, 0));
        offering_day_.assign(offerings.size(), std::vector<int>(D_, 0));
        section_day_.assign(section_index_.size(), std::vector<int>(D_, 0));

        // Conflict graph: events sharing a teacher or a section can never overlap.
        neighbours_.assign(E_, {});
        for (int a = 0; a < E_; ++a)
            for (int b = a + 1; b < E_; ++b)
                if (teacher_of_[a] == teacher_of_[b] || section_of_[a] == section_of_[b]) {
                    neighbours_[a].push_back(b);
                    neighbours_[b].push_back(a);
                }
    }

    void apply_fixed() {
        for (const Busy& b : p_.fixed) {
            for (int k = 0; k < b.length; ++k) {
                int slot = (b.day - 1) * P_ + (b.start_period - 1) + k;
                if (auto it = room_index_.find(b.room_id); b.room_id && it != room_index_.end())
                    ++room_busy_[it->second][slot];
                if (auto it = teacher_index_.find(b.teacher_id); b.teacher_id && it != teacher_index_.end())
                    ++teacher_busy_[it->second][slot];
                if (auto it = section_index_.find(b.section_id); b.section_id && it != section_index_.end())
                    ++section_busy_[it->second][slot];
            }
        }
        for (const Unavailable& u : p_.teacher_unavailable)
            if (auto it = teacher_index_.find(u.teacher_id); it != teacher_index_.end())
                teacher_unavailable_[it->second][(u.day - 1) * P_ + (u.period - 1)] = 1;
    }

    bool block_crosses_break(int start, int length) const {
        for (int k = start; k < start + length - 1; ++k)
            if (break_after_[k]) return true;
        return false;
    }

    bool teacher_available(int t, int day, int start, int length) const {
        for (int k = 0; k < length; ++k)
            if (teacher_unavailable_[t][day * P_ + start + k]) return false;
        return true;
    }

    // Static domain: every position allowed by rules that never change during search.
    void build_domains() {
        domain_.assign(E_, {});
        reason_.assign(E_, "");
        for (int e = 0; e < E_; ++e) {
            const Event& ev = p_.events[e];
            std::vector<int> rooms;
            for (int r = 0; r < R_; ++r)
                if (room_fits_kind(p_.rooms[r].type, ev.kind) && p_.rooms[r].capacity >= ev.size) rooms.push_back(r);
            // Best fit first: keep big rooms free for big sections.
            std::stable_sort(rooms.begin(), rooms.end(),
                             [&](int a, int b) { return p_.rooms[a].capacity < p_.rooms[b].capacity; });

            for (int d = 0; d < D_; ++d)
                for (int s = 0; s + ev.length <= P_; ++s) {
                    if (block_crosses_break(s, ev.length)) continue;
                    if (!teacher_available(teacher_of_[e], d, s, ev.length)) continue;
                    for (int r : rooms)
                        if (free(e, Candidate{d, s, r})) domain_[e].push_back({d, s, r});
                }
            if (domain_[e].empty()) reason_[e] = static_reason(e);
        }
    }

    std::string static_reason(int e) const {
        const Event& ev = p_.events[e];
        int largest = -1;
        for (const Room& r : p_.rooms)
            if (room_fits_kind(r.type, ev.kind)) largest = std::max(largest, r.capacity);
        if (largest < 0) return std::string("no ") + kind_room_name(ev.kind) + " exists";
        if (largest < ev.size)
            return "no " + std::string(kind_room_name(ev.kind)) + " holds " + std::to_string(ev.size) +
                   " students (largest holds " + std::to_string(largest) + ")";
        bool fits_somewhere = false;
        for (int s = 0; s + ev.length <= P_; ++s)
            if (!block_crosses_break(s, ev.length)) fits_somewhere = true;
        if (!fits_somewhere)
            return "a block of " + std::to_string(ev.length) + " periods does not fit between breaks";
        bool teacher_ok = false;
        for (int d = 0; d < D_ && !teacher_ok; ++d)
            for (int s = 0; s + ev.length <= P_; ++s)
                if (!block_crosses_break(s, ev.length) && teacher_available(teacher_of_[e], d, s, ev.length)) {
                    teacher_ok = true;
                    break;
                }
        if (!teacher_ok) return "the teacher is unavailable at every possible time";
        return "every possible time is already taken by fixed slots";
    }

    // ---------------------------------------------------------------- state
    bool free(int e, const Candidate& c) const {
        const int t = teacher_of_[e], s = section_of_[e], len = p_.events[e].length;
        const int base = c.day * P_ + c.start;
        for (int k = 0; k < len; ++k) {
            const int slot = base + k;
            if (room_busy_[c.room][slot] || teacher_busy_[t][slot] || section_busy_[s][slot]) return false;
        }
        return true;
    }

    void mark(int e, const Candidate& c, int delta) {
        const int t = teacher_of_[e], s = section_of_[e], len = p_.events[e].length;
        const int base = c.day * P_ + c.start;
        for (int k = 0; k < len; ++k) {
            room_busy_[c.room][base + k] += delta;
            teacher_busy_[t][base + k] += delta;
            section_busy_[s][base + k] += delta;
        }
        offering_day_[offering_of_[e]][c.day] += delta;
        section_day_[s][c.day] += delta * len;
    }

    void assign(int e, int value) {
        assigned_[e] = value;
        mark(e, domain_[e][value], +1);
    }

    void unassign(int e) {
        mark(e, domain_[e][assigned_[e]], -1);
        assigned_[e] = -1;
    }

    // ---------------------------------------------------------------- search
    // MRV with degree tie-break. Returns -1 when every searchable event is placed.
    int select_variable(int& best_count) const {
        if (p_.order == VariableOrder::InputOrder) {
            // Baseline: the first unplaced event, whatever its options.
            for (int e = 0; e < E_; ++e) {
                if (assigned_[e] >= 0 || domain_[e].empty() || given_up_.count(e)) continue;
                best_count = 0;
                for (const Candidate& c : domain_[e]) best_count += free(e, c) ? 1 : 0;
                return e;
            }
            best_count = 0;
            return -1;
        }
        int best = -1, best_degree = -1;
        best_count = INT_MAX;
        for (int e = 0; e < E_; ++e) {
            if (assigned_[e] >= 0 || domain_[e].empty() || given_up_.count(e)) continue;
            int count = 0;
            for (const Candidate& c : domain_[e])
                if (free(e, c) && ++count > best_count) break;  // cannot beat current best
            int degree = 0;
            for (int n : neighbours_[e]) degree += assigned_[n] < 0 ? 1 : 0;
            if (count < best_count || (count == best_count && degree > best_degree)) {
                best = e;
                best_count = count;
                best_degree = degree;
                if (count == 0) return best;  // dead end: no need to look further
            }
        }
        return best;
    }

    // Soft constraints decide the order in which positions are tried.
    std::vector<int> ordered_values(int e) const {
        std::vector<std::pair<int, int>> scored;  // (score, value index)
        const int o = offering_of_[e], s = section_of_[e];
        for (int v = 0; v < static_cast<int>(domain_[e].size()); ++v) {
            const Candidate& c = domain_[e][v];
            if (!free(e, c)) continue;
            int score = offering_day_[o][c.day] * 1000   // same course again that day
                      + section_day_[s][c.day] * 10      // balance the section's days
                      + c.start                          // prefer earlier periods
                      + (p_.rooms[c.room].capacity - p_.events[e].size) / 20;  // tight room fit
            scored.push_back({score, v});
        }
        std::stable_sort(scored.begin(), scored.end());
        std::vector<int> out;
        out.reserve(scored.size());
        for (auto& sv : scored) out.push_back(sv.second);
        return out;
    }

    bool out_of_budget() {
        if (aborted_) return true;
        if (stats_.nodes >= p_.node_limit) aborted_ = true;
        if ((stats_.nodes & 255) == 0 && std::chrono::steady_clock::now() - start_ >= p_.time_limit) aborted_ = true;
        stats_.limit_reached = aborted_;
        return aborted_;
    }

    bool search(int placed) {
        ++stats_.nodes;
        if (placed > best_count_) {
            best_count_ = placed;
            best_assigned_ = assigned_;
        }
        if (placed == searchable_) return true;
        if (out_of_budget()) return false;

        int count = 0;
        const int e = select_variable(count);
        if (e < 0) return true;
        if (count == 0) {
            ++stats_.backtracks;
            return false;
        }
        for (int v : ordered_values(e)) {
            assign(e, v);
            if (search(placed + 1)) return true;
            unassign(e);
            if (aborted_) return false;
        }
        ++stats_.backtracks;
        return false;
    }

    // Used when search could not finish: place what still fits, report the rest.
    void greedy_complete() {
        for (;;) {
            int count = 0;
            const int e = select_variable(count);
            if (e < 0) return;
            if (count == 0) {
                given_up_.insert(e);
                continue;
            }
            assign(e, ordered_values(e).front());
        }
    }

    std::string dynamic_reason(int /*event*/) const {
        if (aborted_)
            return "search limit reached; no time found where the room, teacher and section are all free";
        return "no time where a suitable room, the teacher and the section are all free";
    }

    const Problem& p_;
    int D_, P_, E_ = 0, R_ = 0;
    std::vector<char> break_after_;
    std::unordered_map<std::int64_t, int> room_index_, teacher_index_, section_index_;
    std::vector<int> teacher_of_, section_of_, offering_of_;
    std::vector<std::vector<int>> room_busy_, teacher_busy_, section_busy_, offering_day_, section_day_;
    std::vector<std::vector<char>> teacher_unavailable_;
    std::vector<std::vector<int>> neighbours_;
    std::vector<std::vector<Candidate>> domain_;
    std::vector<std::string> reason_;
    std::vector<int> assigned_, best_assigned_;
    std::set<int> given_up_;
    int best_count_ = -1, searchable_ = 0;
    bool aborted_ = false;
    Stats stats_;
    std::chrono::steady_clock::time_point start_;
};

}  // namespace

Solution solve(const Problem& problem) {
    Solver solver(problem);
    return solver.run();
}

std::vector<std::string> validate(const Problem& p, const std::vector<Placement>& placements) {
    std::vector<std::string> v;
    std::map<int, const Event*> events;
    for (const Event& e : p.events) events[e.id] = &e;
    std::map<std::int64_t, const Room*> rooms;
    for (const Room& r : p.rooms) rooms[r.id] = &r;
    std::set<int> breaks(p.break_after.begin(), p.break_after.end());
    std::set<std::tuple<std::int64_t, int, int>> unavailable;
    for (const Unavailable& u : p.teacher_unavailable) unavailable.insert({u.teacher_id, u.day, u.period});

    // (resource kind, id, day, period) -> who holds it
    std::map<std::tuple<char, std::int64_t, int, int>, std::string> held;
    auto claim = [&](char kind, std::int64_t id, int day, int period, const std::string& who) {
        if (!id) return;
        auto key = std::make_tuple(kind, id, day, period);
        auto it = held.find(key);
        if (it != held.end()) {
            const char* name = kind == 'r' ? "room " : kind == 't' ? "teacher " : "section ";
            v.push_back(who + " overlaps " + it->second + " (" + name + std::to_string(id) + ", day " +
                        std::to_string(day) + ", period " + std::to_string(period) + ")");
        } else {
            held[key] = who;
        }
    };
    int fixed_no = 0;
    for (const Busy& b : p.fixed) {
        std::string who = "fixed slot #" + std::to_string(++fixed_no);
        for (int k = 0; k < b.length; ++k) {
            claim('r', b.room_id, b.day, b.start_period + k, who);
            claim('t', b.teacher_id, b.day, b.start_period + k, who);
            claim('s', b.section_id, b.day, b.start_period + k, who);
        }
    }

    std::set<int> seen;
    for (const Placement& pl : placements) {
        std::string who = "event " + std::to_string(pl.event_id);
        auto ei = events.find(pl.event_id);
        if (ei == events.end()) { v.push_back(who + " does not exist"); continue; }
        if (!seen.insert(pl.event_id).second) { v.push_back(who + " placed twice"); continue; }
        const Event& e = *ei->second;
        auto ri = rooms.find(pl.room_id);
        if (ri == rooms.end()) { v.push_back(who + " uses unknown room"); continue; }
        const Room& r = *ri->second;
        if (!room_fits_kind(r.type, e.kind)) v.push_back(who + " is in the wrong room type");
        if (r.capacity < e.size) v.push_back(who + " does not fit in its room");
        if (pl.day < 1 || pl.day > p.days || pl.start_period < 1 || pl.start_period + e.length - 1 > p.periods_per_day) {
            v.push_back(who + " is outside the grid");
            continue;
        }
        for (int k = pl.start_period; k < pl.start_period + e.length - 1; ++k)
            if (breaks.count(k)) v.push_back(who + " crosses a break");
        for (int k = 0; k < e.length; ++k) {
            int period = pl.start_period + k;
            if (unavailable.count({e.teacher_id, pl.day, period})) v.push_back(who + " uses unavailable teacher time");
            claim('r', pl.room_id, pl.day, period, who);
            claim('t', e.teacher_id, pl.day, period, who);
            claim('s', e.section_id, pl.day, period, who);
        }
    }
    return v;
}

}  // namespace classos::timetable

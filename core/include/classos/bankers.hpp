#pragma once
// Banker's Algorithm for lab-equipment allocation.
//
// Mapping to ClassOS:
//   resource types  -> equipment types in one lab (oscilloscopes, Arduino kits, ...)
//   total           -> units the lab owns (room_equipment.total_units)
//   client          -> a lab session that declared its MAXIMUM need when booked
//   request         -> the session asks for more units mid-session
//
// Limitation (state this in your report): Banker's only works when each client
// declares its maximum claim in advance. A request beyond that claim is rejected.
//
// Scope: this class is an in-memory decision engine. The database remains the
// source of truth for what is actually allocated (see docs/schema.md).

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <mutex>
#include <string>
#include <vector>

namespace classos {

enum class RequestStatus {
    Granted,
    ExceedsClaim,   // asks for more than the declared maximum: a client error
    Insufficient,   // not enough free units right now: may succeed later
    Unsafe,         // granting could lead to deadlock: may succeed later
    ClientInactive  // client already released everything and left
};

struct RequestResult {
    RequestStatus status;
    std::string reason;
    bool granted() const { return status == RequestStatus::Granted; }
};

class BankersAllocator {
public:
    explicit BankersAllocator(std::vector<int> total);

    // Registers a client with its maximum claim. Returns the client id.
    // Throws std::invalid_argument if the claim exceeds the lab's total units.
    std::size_t add_client(std::vector<int> max_claim);

    // Non-blocking: decides immediately. Preferred for web requests, because
    // blocking a server thread while waiting for equipment wastes threads.
    RequestResult request(std::size_t client, const std::vector<int>& req);

    // Blocking variant: waits (on a condition variable) until the request can be
    // granted safely, or until the timeout expires. Never waits on ExceedsClaim.
    RequestResult request_wait(std::size_t client, const std::vector<int>& req,
                               std::chrono::milliseconds timeout);

    void release(std::size_t client, const std::vector<int>& rel);
    void release_all(std::size_t client);  // session ended

    bool is_safe_state() const;
    std::vector<std::size_t> safe_sequence() const;
    std::vector<int> available() const;
    std::vector<int> allocation_of(std::size_t client) const;

private:
    RequestResult try_grant_locked(std::size_t client, const std::vector<int>& req);
    bool safe_locked(std::vector<std::size_t>* sequence) const;
    void check_client_locked(std::size_t client) const;
    void check_dims(const std::vector<int>& v, const char* what) const;

    mutable std::mutex mu_;
    std::condition_variable released_;
    std::vector<int> total_;
    std::vector<int> available_;
    std::vector<std::vector<int>> max_;
    std::vector<std::vector<int>> alloc_;
    std::vector<bool> active_;
};

}  // namespace classos

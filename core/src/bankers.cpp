#include "classos/bankers.hpp"

#include <stdexcept>

namespace classos {

namespace {
bool leq(const std::vector<int>& a, const std::vector<int>& b) {
    for (std::size_t i = 0; i < a.size(); ++i)
        if (a[i] > b[i]) return false;
    return true;
}
}  // namespace

BankersAllocator::BankersAllocator(std::vector<int> total)
    : total_(std::move(total)), available_(total_) {
    if (total_.empty()) throw std::invalid_argument("at least one resource type required");
    for (int t : total_)
        if (t < 0) throw std::invalid_argument("total units cannot be negative");
}

void BankersAllocator::check_dims(const std::vector<int>& v, const char* what) const {
    if (v.size() != total_.size())
        throw std::invalid_argument(std::string(what) + ": wrong number of resource types");
    for (int x : v)
        if (x < 0) throw std::invalid_argument(std::string(what) + ": negative units");
}

void BankersAllocator::check_client_locked(std::size_t client) const {
    if (client >= max_.size()) throw std::out_of_range("unknown client id");
}

std::size_t BankersAllocator::add_client(std::vector<int> max_claim) {
    check_dims(max_claim, "max_claim");
    if (!leq(max_claim, total_))
        throw std::invalid_argument("max_claim exceeds total units of the lab");
    std::lock_guard<std::mutex> lock(mu_);
    // Adding a client with zero allocation cannot make the state unsafe: every
    // existing client can still finish, after which all units are free.
    max_.push_back(std::move(max_claim));
    alloc_.emplace_back(total_.size(), 0);
    active_.push_back(true);
    return max_.size() - 1;
}

RequestResult BankersAllocator::try_grant_locked(std::size_t client, const std::vector<int>& req) {
    if (!active_[client]) return {RequestStatus::ClientInactive, "client has already finished"};

    for (std::size_t j = 0; j < req.size(); ++j)
        if (alloc_[client][j] + req[j] > max_[client][j])
            return {RequestStatus::ExceedsClaim,
                    "request exceeds declared maximum for resource " + std::to_string(j)};

    if (!leq(req, available_))
        return {RequestStatus::Insufficient, "not enough free units right now"};

    // Pretend to allocate, then check safety; roll back if unsafe.
    for (std::size_t j = 0; j < req.size(); ++j) {
        available_[j] -= req[j];
        alloc_[client][j] += req[j];
    }
    if (safe_locked(nullptr)) return {RequestStatus::Granted, "granted"};

    for (std::size_t j = 0; j < req.size(); ++j) {
        available_[j] += req[j];
        alloc_[client][j] -= req[j];
    }
    return {RequestStatus::Unsafe, "granting would leave the system in an unsafe state"};
}

RequestResult BankersAllocator::request(std::size_t client, const std::vector<int>& req) {
    check_dims(req, "request");
    std::lock_guard<std::mutex> lock(mu_);
    check_client_locked(client);
    return try_grant_locked(client, req);
}

RequestResult BankersAllocator::request_wait(std::size_t client, const std::vector<int>& req,
                                             std::chrono::milliseconds timeout) {
    check_dims(req, "request");
    std::unique_lock<std::mutex> lock(mu_);
    check_client_locked(client);
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    for (;;) {
        RequestResult r = try_grant_locked(client, req);
        // Only Insufficient/Unsafe can change when someone releases units.
        if (r.status != RequestStatus::Insufficient && r.status != RequestStatus::Unsafe) return r;
        // wait_until handles spurious wakeups: we simply loop and re-check.
        if (released_.wait_until(lock, deadline) == std::cv_status::timeout) {
            return try_grant_locked(client, req);  // one last check at the deadline
        }
    }
}

void BankersAllocator::release(std::size_t client, const std::vector<int>& rel) {
    check_dims(rel, "release");
    {
        std::lock_guard<std::mutex> lock(mu_);
        check_client_locked(client);
        if (!leq(rel, alloc_[client]))
            throw std::invalid_argument("cannot release more than currently allocated");
        for (std::size_t j = 0; j < rel.size(); ++j) {
            alloc_[client][j] -= rel[j];
            available_[j] += rel[j];
        }
    }
    released_.notify_all();  // notify outside the lock to avoid waking into contention
}

void BankersAllocator::release_all(std::size_t client) {
    {
        std::lock_guard<std::mutex> lock(mu_);
        check_client_locked(client);
        for (std::size_t j = 0; j < total_.size(); ++j) {
            available_[j] += alloc_[client][j];
            alloc_[client][j] = 0;
        }
        active_[client] = false;
    }
    released_.notify_all();
}

// Safety algorithm: O(n^2 * m) for n clients and m resource types.
bool BankersAllocator::safe_locked(std::vector<std::size_t>* sequence) const {
    std::vector<int> work = available_;
    std::vector<bool> finished(max_.size());
    for (std::size_t i = 0; i < max_.size(); ++i) finished[i] = !active_[i];

    std::size_t remaining = 0;
    for (bool f : finished) remaining += f ? 0 : 1;

    bool progress = true;
    while (remaining > 0 && progress) {
        progress = false;
        for (std::size_t i = 0; i < max_.size(); ++i) {
            if (finished[i]) continue;
            bool can_finish = true;
            for (std::size_t j = 0; j < work.size(); ++j)
                if (max_[i][j] - alloc_[i][j] > work[j]) { can_finish = false; break; }
            if (!can_finish) continue;
            for (std::size_t j = 0; j < work.size(); ++j) work[j] += alloc_[i][j];
            finished[i] = true;
            --remaining;
            progress = true;
            if (sequence) sequence->push_back(i);
        }
    }
    return remaining == 0;
}

bool BankersAllocator::is_safe_state() const {
    std::lock_guard<std::mutex> lock(mu_);
    return safe_locked(nullptr);
}

std::optional<std::vector<std::size_t>> BankersAllocator::safe_sequence() const {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<std::size_t> seq;
    if (!safe_locked(&seq)) return std::nullopt;
    return seq;
}

std::vector<int> BankersAllocator::available() const {
    std::lock_guard<std::mutex> lock(mu_);
    return available_;
}

std::vector<int> BankersAllocator::allocation_of(std::size_t client) const {
    std::lock_guard<std::mutex> lock(mu_);
    check_client_locked(client);
    return alloc_[client];
}

}  // namespace classos

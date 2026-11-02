// Minimal self-contained test harness (no external downloads needed).
// Can be migrated to GoogleTest later without changing the test logic.
#include "classos/bankers.hpp"

#include <atomic>
#include <iostream>
#include <random>
#include <stdexcept>
#include <thread>

using namespace classos;

static int failures = 0;
#define CHECK(cond)                                                                  \
    do {                                                                             \
        if (!(cond)) {                                                               \
            ++failures;                                                              \
            std::cerr << "FAIL " << __FILE__ << ":" << __LINE__ << "  " #cond "\n"; \
        }                                                                            \
    } while (0)

// Textbook example (Silberschatz, Operating System Concepts): 5 clients, 3 types.
// The allocator owns a mutex, so it is neither copyable nor movable: fill it in place.
static void load_textbook(BankersAllocator& b, std::vector<std::size_t>& ids) {
    const std::vector<std::vector<int>> max = {{7, 5, 3}, {3, 2, 2}, {9, 0, 2}, {2, 2, 2}, {4, 3, 3}};
    const std::vector<std::vector<int>> alloc = {{0, 1, 0}, {2, 0, 0}, {3, 0, 2}, {2, 1, 1}, {0, 0, 2}};
    for (auto& m : max) ids.push_back(b.add_client(m));
    for (std::size_t i = 0; i < alloc.size(); ++i) CHECK(b.request(ids[i], alloc[i]).granted());
}

static void test_textbook_scenario() {
    BankersAllocator b({10, 5, 7});
    std::vector<std::size_t> p;
    load_textbook(b, p);
    CHECK((b.available() == std::vector<int>{3, 3, 2}));
    CHECK(b.is_safe_state());

    CHECK(b.request(p[1], {1, 0, 2}).granted());                          // P1 (1,0,2): safe
    CHECK((b.available() == std::vector<int>{2, 3, 0}));
    CHECK(b.request(p[4], {3, 3, 0}).status == RequestStatus::Insufficient);  // P4 (3,3,0)
    CHECK(b.request(p[0], {0, 2, 0}).status == RequestStatus::Unsafe);        // P0 (0,2,0)
    CHECK((b.available() == std::vector<int>{2, 3, 0}));  // unsafe request was rolled back
}

static void test_claim_violations() {
    BankersAllocator b({5, 5});
    auto c = b.add_client({2, 2});
    CHECK(b.request(c, {3, 0}).status == RequestStatus::ExceedsClaim);
    CHECK(b.request(c, {2, 2}).granted());
    CHECK(b.request(c, {1, 0}).status == RequestStatus::ExceedsClaim);

    bool threw = false;
    try { b.add_client({6, 0}); } catch (const std::invalid_argument&) { threw = true; }
    CHECK(threw);

    threw = false;
    try { b.release(c, {3, 0}); } catch (const std::invalid_argument&) { threw = true; }
    CHECK(threw);
}

static void test_release_all() {
    BankersAllocator b({4});
    auto a = b.add_client({4});
    CHECK(b.request(a, {3}).granted());
    b.release_all(a);
    CHECK((b.available() == std::vector<int>{4}));
    CHECK(b.request(a, {1}).status == RequestStatus::ClientInactive);
}

static void test_request_wait_wakes_on_release() {
    BankersAllocator b({2});
    auto holder = b.add_client({2});
    auto waiter = b.add_client({2});
    CHECK(b.request(holder, {2}).granted());

    std::thread releaser([&] {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        b.release_all(holder);
    });
    auto r = b.request_wait(waiter, {2}, std::chrono::milliseconds(2000));
    releaser.join();
    CHECK(r.granted());

    auto r2 = b.request_wait(holder, {1}, std::chrono::milliseconds(10));
    CHECK(r2.status == RequestStatus::ClientInactive);  // does not wait

    BankersAllocator c({1});
    auto x = c.add_client({1});
    auto y = c.add_client({1});
    CHECK(c.request(x, {1}).granted());
    auto r3 = c.request_wait(y, {1}, std::chrono::milliseconds(30));
    CHECK(r3.status == RequestStatus::Insufficient);  // times out
}

// Many threads hammer one allocator. Invariants: no unit is created or lost,
// and the state is safe after every grant.
static void test_concurrent_stress() {
    const std::vector<int> total = {8, 6, 5};
    BankersAllocator b(total);
    const int threads = 8, iterations = 2000;
    std::vector<std::size_t> ids;
    for (int t = 0; t < threads; ++t) ids.push_back(b.add_client({4, 3, 3}));

    std::atomic<int> unsafe_seen{0}, granted{0};
    std::vector<std::thread> pool;
    for (int t = 0; t < threads; ++t) {
        pool.emplace_back([&, t] {
            std::mt19937 rng(static_cast<unsigned>(t * 7919 + 1));
            std::uniform_int_distribution<int> d(0, 1);
            for (int i = 0; i < iterations; ++i) {
                std::vector<int> req = {d(rng), d(rng), d(rng)};
                auto r = b.request(ids[t], req);
                if (r.granted()) {
                    ++granted;
                    if (!b.is_safe_state()) ++unsafe_seen;
                }
                if (i % 3 == 0) b.release(ids[t], b.allocation_of(ids[t]));
            }
            b.release_all(ids[t]);
        });
    }
    for (auto& th : pool) th.join();
    CHECK(unsafe_seen.load() == 0);
    CHECK(granted.load() > 0);
    CHECK(b.available() == total);
}

int main() {
    test_textbook_scenario();
    test_claim_violations();
    test_release_all();
    test_request_wait_wakes_on_release();
    test_concurrent_stress();
    if (failures == 0) std::cout << "All Banker's tests passed\n";
    return failures == 0 ? 0 : 1;
}

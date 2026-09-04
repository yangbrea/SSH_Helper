#include "ssh/ssh_runtime.h"

#include <atomic>
#include <cassert>
#include <thread>
#include <vector>

int main() {
    // All work posted before shutdown runs serially on the owner thread.
    {
        sshnative::SshNativeSession session;
        std::thread::id owner_id;
        constexpr int kTaskCount = 200;
        std::vector<std::thread::id> ids(kTaskCount);

        for (int i = 0; i < kTaskCount; ++i) {
            const bool accepted = session.post([&, i] {
                ids[i] = std::this_thread::get_id();
            });
            assert(accepted);
        }

        session.shutdown();
        owner_id = ids[0];
        for (int i = 0; i < kTaskCount; ++i) {
            assert(ids[i] != std::thread::id{});
            assert(ids[i] == owner_id);
        }
    }

    // Concurrent producers are accepted and all queued work is drained.
    {
        sshnative::SshNativeSession session;
        constexpr int kThreads = 8;
        constexpr int kPerThread = 500;
        std::atomic<int> executed{0};
        std::vector<std::thread> producers;

        for (int t = 0; t < kThreads; ++t) {
            producers.emplace_back([&] {
                for (int i = 0; i < kPerThread; ++i) {
                    const bool accepted = session.post([&] { executed.fetch_add(1); });
                    assert(accepted);
                }
            });
        }
        for (auto& producer : producers) producer.join();

        session.shutdown();
        assert(executed.load() == kThreads * kPerThread);
    }

    // Shutdown is idempotent and rejects new work afterwards.
    {
        sshnative::SshNativeSession session;
        int executed = 0;
        assert(session.post([&] { ++executed; }));
        session.shutdown();
        session.shutdown();
        assert(executed == 1);
        assert(!session.post([&] { ++executed; }));
        assert(executed == 1);
    }

    // Multiple independent sessions do not share an owner thread.
    {
        sshnative::SshNativeSession first;
        sshnative::SshNativeSession second;
        std::thread::id first_id;
        std::thread::id second_id;
        assert(first.post([&] { first_id = std::this_thread::get_id(); }));
        assert(second.post([&] { second_id = std::this_thread::get_id(); }));
        first.shutdown();
        second.shutdown();
        assert(first_id != std::thread::id{});
        assert(second_id != std::thread::id{});
        assert(first_id != second_id);
    }

    // Repeated close through the public factory path is safe.
    {
        auto session = sshnative::createSession();
        session->shutdown();
        session->shutdown();
    }
}

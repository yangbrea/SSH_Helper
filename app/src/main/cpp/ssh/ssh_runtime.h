#pragma once

#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>

namespace sshnative {

// Single-owner native SSH session runtime.
//
// Every SshNativeSession owns exactly one event-loop thread. Work is submitted
// through post() and executed serially on that thread; shutdown() is idempotent
// and waits for already-queued work to finish before joining the thread. This
// is the foundation that later steps will extend with libssh2 non-blocking I/O,
// poll fds and command cancellation.
class SshNativeSession {
public:
    SshNativeSession();
    ~SshNativeSession();

    SshNativeSession(const SshNativeSession&) = delete;
    SshNativeSession& operator=(const SshNativeSession&) = delete;

    // Enqueue work to run on the owner thread. Returns false after shutdown.
    bool post(std::function<void()> task);

    // Stop accepting new work, run remaining queued work, then join the thread.
    // Safe to call more than once and from any thread except the owner thread.
    void shutdown();

private:
    void loop();
    void wake();

    int wake_read_ = -1;
    int wake_write_ = -1;
    std::mutex mutex_;
    std::deque<std::function<void()>> queue_;
    bool shutting_down_ = false;
    std::thread thread_;
};

std::shared_ptr<SshNativeSession> createSession();

} // namespace sshnative

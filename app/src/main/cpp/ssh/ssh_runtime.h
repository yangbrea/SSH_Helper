#pragma once

#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>

namespace sshnative {

// Single-owner native SSH session runtime.
//
// Every SshNativeSession owns exactly one event-loop thread. Work is submitted
// through post()/postRequest() and executed serially on that thread. Requests
// can be canceled by request id while they are still queued. shutdown() is
// idempotent and waits for already-queued work to finish before joining the
// thread. This is the foundation that later steps will extend with libssh2
// non-blocking I/O, poll fds and command timeout handling.
class SshNativeSession {
public:
    using RequestId = uint64_t;

    SshNativeSession();
    ~SshNativeSession();

    SshNativeSession(const SshNativeSession&) = delete;
    SshNativeSession& operator=(const SshNativeSession&) = delete;

    // Enqueue fire-and-forget work to run on the owner thread. Returns false
    // after shutdown.
    bool post(std::function<void()> task);

    // Enqueue cancellable work and return its request id (0 after shutdown).
    RequestId postRequest(std::function<void()> task);

    // Cancel a queued request. Returns true if the request was still pending.
    // Cancellation is best-effort once the request has started executing.
    bool cancel(RequestId id);

    // Stop accepting new work, run remaining queued work, then join the thread.
    // Safe to call more than once and from any thread except the owner thread.
    void shutdown();

private:
    struct Command {
        RequestId id = 0;
        std::function<void()> task;
        std::atomic<bool> canceled{false};
    };

    void loop();
    void wake();

    int wake_read_ = -1;
    int wake_write_ = -1;
    std::mutex mutex_;
    std::deque<std::shared_ptr<Command>> queue_;
    std::unordered_map<RequestId, std::shared_ptr<Command>> pending_;
    RequestId next_request_id_ = 1;
    bool shutting_down_ = false;
    std::thread thread_;
};

std::shared_ptr<SshNativeSession> createSession();

} // namespace sshnative

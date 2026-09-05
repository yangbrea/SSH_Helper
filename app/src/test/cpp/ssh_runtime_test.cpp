#include "ssh/ssh_runtime.h"

#include <poll.h>
#include <unistd.h>

#include <atomic>
#include <cassert>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <thread>
#include <vector>

using namespace std::chrono_literals;

namespace {

sshnative::RuntimeEvent waitCompletion(sshnative::SshNativeSession& session,
                                       sshnative::RequestId id) {
    const auto stop = sshnative::MonoClock::now() + 2s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!session.waitEvent(&event, 100ms)) continue;
        if (event.kind == sshnative::RuntimeEventKind::kCompletion && event.request_id == id) {
            return event;
        }
    }
    assert(false && "completion timeout");
    return {};
}

class ImmediateOperation final : public sshnative::Operation {
public:
    ImmediateOperation(std::thread::id* thread_id = nullptr,
                       std::atomic<int>* count = nullptr,
                       std::string payload = "ok")
        : thread_id_(thread_id), count_(count), payload_(std::move(payload)) {}

    sshnative::StepResult step(sshnative::LoopContext&, const sshnative::ReadySet&,
                               sshnative::MonoTime) override {
        if (thread_id_) *thread_id_ = std::this_thread::get_id();
        if (count_) count_->fetch_add(1);
        return sshnative::StepResult::complete(payload_);
    }

private:
    std::thread::id* thread_id_;
    std::atomic<int>* count_;
    std::string payload_;
};

class ReadOperation final : public sshnative::Operation {
public:
    explicit ReadOperation(int fd) : fd_(fd) {}

    sshnative::StepResult step(sshnative::LoopContext&, const sshnative::ReadySet& ready,
                               sshnative::MonoTime) override {
        if (!ready.ready(fd_, POLLIN)) {
            return sshnative::StepResult::waitIo({{fd_, POLLIN}});
        }
        char byte = 0;
        assert(read(fd_, &byte, 1) == 1);
        return sshnative::StepResult::complete(std::string(1, byte));
    }

private:
    int fd_;
};

class WaitingOperation final : public sshnative::Operation {
public:
    WaitingOperation(int fd, std::atomic<bool>* started, std::atomic<bool>* cancelled)
        : fd_(fd), started_(started), cancelled_(cancelled) {}

    sshnative::StepResult step(sshnative::LoopContext&, const sshnative::ReadySet&,
                               sshnative::MonoTime) override {
        started_->store(true);
        return sshnative::StepResult::waitIo({{fd_, POLLIN}});
    }

    void onCancel(sshnative::LoopContext&) noexcept override { cancelled_->store(true); }

private:
    int fd_;
    std::atomic<bool>* started_;
    std::atomic<bool>* cancelled_;
};

class TimerOperation final : public sshnative::Operation {
public:
    sshnative::StepResult step(sshnative::LoopContext&, const sshnative::ReadySet&,
                               sshnative::MonoTime) override {
        return sshnative::StepResult::waitTimer(sshnative::MonoTime::max());
    }
};

class EmptyInterestOperation final : public sshnative::Operation {
public:
    sshnative::StepResult step(sshnative::LoopContext&, const sshnative::ReadySet&,
                               sshnative::MonoTime) override {
        return sshnative::StepResult::waitIo({});
    }
};

class ThrowingOperation final : public sshnative::Operation {
public:
    sshnative::StepResult step(sshnative::LoopContext&, const sshnative::ReadySet&,
                               sshnative::MonoTime) override {
        throw std::runtime_error("test failure");
    }
};

class ProgressOperation final : public sshnative::Operation {
public:
    explicit ProgressOperation(int turns) : turns_(turns) {}
    sshnative::StepResult step(sshnative::LoopContext&, const sshnative::ReadySet&,
                               sshnative::MonoTime) override {
        if (turns_-- > 0) return sshnative::StepResult::progress(1);
        return sshnative::StepResult::complete("bulk");
    }
private:
    int turns_;
};

class GateOperation final : public sshnative::Operation {
public:
    GateOperation(std::atomic<bool>* started, std::atomic<bool>* release)
        : started_(started), release_(release) {}
    sshnative::StepResult step(sshnative::LoopContext&, const sshnative::ReadySet&,
                               sshnative::MonoTime) override {
        started_->store(true);
        while (!release_->load()) std::this_thread::yield();
        return sshnative::StepResult::complete();
    }
private:
    std::atomic<bool>* started_;
    std::atomic<bool>* release_;
};

class CountingPoller final : public sshnative::Poller {
public:
    int poll(std::vector<::pollfd>& fds, int timeout_ms) override {
        calls.fetch_add(1);
        return ::poll(fds.data(), static_cast<nfds_t>(fds.size()), timeout_ms);
    }
    std::atomic<int> calls{0};
};

struct CloseLog { std::vector<sshnative::ResourceKind> order; };

class LoggedResource final : public sshnative::RuntimeResource {
public:
    LoggedResource(sshnative::ResourceKind kind, CloseLog* log) : kind_(kind), log_(log) {}
    sshnative::ResourceKind kind() const noexcept override { return kind_; }
    sshnative::StepResult closeStep(const sshnative::ReadySet&, sshnative::MonoTime) override {
        log_->order.push_back(kind_);
        return sshnative::StepResult::complete();
    }
    void forceClose() noexcept override { log_->order.push_back(kind_); }
private:
    sshnative::ResourceKind kind_;
    CloseLog* log_;
};

class RegisterResourcesOperation final : public sshnative::Operation {
public:
    explicit RegisterResourcesOperation(CloseLog* log) : log_(log) {}
    sshnative::StepResult step(sshnative::LoopContext& context,
                               const sshnative::ReadySet&, sshnative::MonoTime) override {
        context.addResource(std::make_unique<LoggedResource>(
            sshnative::ResourceKind::kTransportSocket, log_));
        context.addResource(std::make_unique<LoggedResource>(
            sshnative::ResourceKind::kChannel, log_));
        context.addResource(std::make_unique<LoggedResource>(
            sshnative::ResourceKind::kLibssh2Session, log_));
        context.addResource(std::make_unique<LoggedResource>(
            sshnative::ResourceKind::kSftpHandle, log_));
        return sshnative::StepResult::complete();
    }
private:
    CloseLog* log_;
};

} // namespace

int main() {
    // Owner-thread serialization, immutable completion payload and real poll wakeup.
    {
        sshnative::SshNativeSession session;
        std::thread::id executed_on;
        auto first = session.submit(std::make_unique<ImmediateOperation>(&executed_on));
        assert(first);
        const auto first_event = waitCompletion(session, first.request_id);
        assert(first_event.completion == sshnative::CompletionKind::kSucceeded);
        assert(first_event.payload == "ok");
        assert(executed_on == session.ownerThreadId());

        int fds[2];
        assert(pipe(fds) == 0);
        auto read_request = session.submit(std::make_unique<ReadOperation>(fds[0]));
        assert(read_request);
        const char value = 'x';
        assert(write(fds[1], &value, 1) == 1);
        const auto read_event = waitCompletion(session, read_request.request_id);
        assert(read_event.payload == "x");
        close(fds[0]);
        close(fds[1]);
        session.shutdown();
    }

    // Active cancellation and deadline expiry each produce exactly one terminal event.
    {
        int fds[2];
        assert(pipe(fds) == 0);
        sshnative::SshNativeSession session;
        std::atomic<bool> started{false};
        std::atomic<bool> cancelled{false};
        auto request = session.submit(
            std::make_unique<WaitingOperation>(fds[0], &started, &cancelled));
        assert(request);
        while (!started.load()) std::this_thread::yield();
        assert(session.cancel(request.request_id));
        assert(!session.cancel(request.request_id));
        const auto event = waitCompletion(session, request.request_id);
        assert(event.completion == sshnative::CompletionKind::kCancelled);
        assert(event.error.domain == sshnative::ErrorDomain::kCancelled);
        assert(cancelled.load());

        sshnative::RequestOptions timed;
        timed.deadline = sshnative::MonoClock::now() + 20ms;
        auto deadline = session.submit(std::make_unique<TimerOperation>(), timed);
        const auto deadline_event = waitCompletion(session, deadline.request_id);
        assert(deadline_event.completion == sshnative::CompletionKind::kFailed);
        assert(deadline_event.error.domain == sshnative::ErrorDomain::kTimeout);
        session.shutdown();
        close(fds[0]);
        close(fds[1]);
    }

    // Defensive operation failures do not kill the event loop.
    {
        sshnative::SshNativeSession session;
        auto empty = session.submit(std::make_unique<EmptyInterestOperation>());
        auto throwing = session.submit(std::make_unique<ThrowingOperation>());
        const auto throwing_event = waitCompletion(session, throwing.request_id);
        const auto empty_event = waitCompletion(session, empty.request_id);
        assert(empty_event.error.code == "empty_io_interest");
        assert(throwing_event.error.code == "operation_exception");
        auto still_alive = session.submit(std::make_unique<ImmediateOperation>());
        assert(waitCompletion(session, still_alive.request_id).completion ==
               sshnative::CompletionKind::kSucceeded);
        session.shutdown();
    }

    // Weighted queues prevent an already-running bulk continuation from starving input.
    {
        sshnative::SshNativeSession session;
        sshnative::RequestOptions bulk_options;
        bulk_options.priority = sshnative::Priority::kBulk;
        auto bulk = session.submit(std::make_unique<ProgressOperation>(500), bulk_options);
        sshnative::RequestOptions input_options;
        input_options.priority = sshnative::Priority::kInteractive;
        auto input = session.submit(std::make_unique<ImmediateOperation>(nullptr, nullptr, "input"),
                                    input_options);
        const auto first = waitCompletion(session, input.request_id);
        assert(first.payload == "input");
        assert(waitCompletion(session, bulk.request_id).payload == "bulk");
        session.shutdown();
    }

    // Completion obligations provide bounded backpressure until consumers drain them.
    {
        sshnative::RuntimeLimits limits;
        limits.max_completion_obligations = 2;
        sshnative::SshNativeSession session(limits);
        std::atomic<int> completed{0};
        auto one = session.submit(std::make_unique<ImmediateOperation>(nullptr, &completed));
        auto two = session.submit(std::make_unique<ImmediateOperation>(nullptr, &completed));
        while (completed.load() != 2) std::this_thread::yield();
        auto rejected = session.submit(std::make_unique<ImmediateOperation>());
        assert(!rejected && rejected.error == sshnative::SubmitError::kQueueFull);
        (void)waitCompletion(session, one.request_id);
        auto accepted = session.submit(std::make_unique<ImmediateOperation>());
        assert(accepted);
        (void)waitCompletion(session, two.request_id);
        (void)waitCompletion(session, accepted.request_id);
        session.shutdown();
    }

    // Dependency order is deterministic: child handles/channels before session/socket.
    {
        CloseLog log;
        sshnative::SshNativeSession session;
        auto request = session.submit(std::make_unique<RegisterResourcesOperation>(&log));
        (void)waitCompletion(session, request.request_id);
        session.shutdown();
        const std::vector<sshnative::ResourceKind> expected{
            sshnative::ResourceKind::kSftpHandle,
            sshnative::ResourceKind::kChannel,
            sshnative::ResourceKind::kLibssh2Session,
            sshnative::ResourceKind::kTransportSocket,
        };
        assert(log.order == expected);
        assert(!session.acceptingCommands());
        assert(session.state() == sshnative::SessionState::kClosed);
        assert(!session.submit(std::make_unique<ImmediateOperation>()));
        session.shutdown();
    }

    // A concurrent close wakes the loop and cancels work which has not started.
    {
        sshnative::SshNativeSession session;
        std::atomic<bool> started{false};
        std::atomic<bool> release{false};
        std::atomic<int> queued_runs{0};
        auto active = session.submit(std::make_unique<GateOperation>(&started, &release));
        while (!started.load()) std::this_thread::yield();
        auto queued = session.submit(
            std::make_unique<ImmediateOperation>(nullptr, &queued_runs));
        std::thread closer([&session] { session.shutdown(); });
        while (session.acceptingCommands()) std::this_thread::yield();
        release.store(true);
        closer.join();
        assert(queued_runs.load() == 0);
        assert(waitCompletion(session, active.request_id).completion ==
               sshnative::CompletionKind::kSucceeded);
        assert(waitCompletion(session, queued.request_id).completion ==
               sshnative::CompletionKind::kCancelled);
    }

    // With no commands or deadlines the loop blocks in poll instead of ticking.
    {
        auto poller = std::make_shared<CountingPoller>();
        sshnative::RuntimeDependencies dependencies;
        dependencies.poller = poller;
        sshnative::SshNativeSession session({}, dependencies);
        std::this_thread::sleep_for(30ms);
        assert(poller->calls.load() <= 2);
        session.shutdown();
    }

    // Buffer watermarks bound producer growth and resume below the low watermark.
    {
        sshnative::WatermarkedBuffer buffer(2, 4);
        const char data[] = {'a', 'b', 'c', 'd'};
        assert(buffer.append(data, sizeof(data)));
        assert(buffer.producerPaused());
        assert(!buffer.append(data, 1));
        char output[3]{};
        assert(buffer.consume(output, sizeof(output)) == 3);
        assert(!buffer.producerPaused());
        assert(output[0] == 'a' && output[2] == 'c');
    }

    // Sessions own distinct loop threads.
    {
        sshnative::SshNativeSession first;
        sshnative::SshNativeSession second;
        std::thread::id first_id;
        std::thread::id second_id;
        auto a = first.submit(std::make_unique<ImmediateOperation>(&first_id));
        auto b = second.submit(std::make_unique<ImmediateOperation>(&second_id));
        (void)waitCompletion(first, a.request_id);
        (void)waitCompletion(second, b.request_id);
        assert(first_id != second_id);
        first.shutdown();
        second.shutdown();
    }
}

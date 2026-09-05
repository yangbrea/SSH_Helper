#pragma once

#include "ssh_error.h"

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <poll.h>
#include <string>
#include <thread>
#include <vector>

namespace sshnative {

using RequestId = uint64_t;
using MonoClock = std::chrono::steady_clock;
using MonoTime = MonoClock::time_point;

enum class SessionState {
    kCreated = 0,
    kResolving,
    kConnecting,
    kProxyNegotiating,
    kHandshaking,
    kVerifyingHostKey,
    kAuthenticating,
    kReady,
    kClosing,
    kClosed,
    kFailed,
};

enum class ChannelState { kOpening = 0, kOpen, kEof, kClosing, kClosed, kFailed };
enum class Priority { kInteractive = 0, kNormal, kBulk };
enum class CancelScope { kRequest = 0, kChannel, kTransport };
enum class CompletionKind { kSucceeded = 0, kFailed, kCancelled };
enum class RuntimeEventKind { kCompletion = 0, kSessionStateChanged };

struct PollInterest {
    int fd = -1;
    short events = 0;
};

using IoInterest = std::vector<PollInterest>;

class ReadySet {
public:
    bool ready(int fd, short events) const noexcept;

private:
    friend class SshNativeSession;
    struct Item { int fd; short revents; };
    std::vector<Item> items_;
};

enum class StepKind { kComplete = 0, kProgress, kNoProgress, kWaitIo, kWaitTimer, kFailed };

struct StepResult {
    StepKind kind = StepKind::kNoProgress;
    size_t bytes = 0;
    IoInterest interest;
    MonoTime wake_at{};
    std::string payload;
    SshError error;

    static StepResult complete(std::string payload = {});
    static StepResult progress(size_t bytes = 0);
    static StepResult noProgress();
    static StepResult waitIo(IoInterest interest);
    static StepResult waitTimer(MonoTime wake_at);
    static StepResult failed(SshError error);
};

enum class ResourceKind {
    kSftpHandle = 0,
    kChannel,
    kSftpSession,
    kRemoteListener,
    kLibssh2Session,
    kTransportSocket,
};

class RuntimeResource {
public:
    virtual ~RuntimeResource() = default;
    virtual ResourceKind kind() const noexcept = 0;
    virtual StepResult closeStep(const ReadySet& ready, MonoTime now) = 0;
    virtual void forceClose() noexcept = 0;
};

class LoopContext {
public:
    SessionState state() const noexcept;
    void transition(SessionState next);
    void addResource(std::unique_ptr<RuntimeResource> resource);

    // Transfers ownership of an established non-blocking transport socket to
    // the runtime. The socket is stored between requests so a later operation
    // can continue (SSH handshake/auth/exec) on the same transport.
    void storeTransportFd(int fd);
    // Takes ownership of the previously stored transport socket, or returns -1.
    int takeTransportFd();

    // Stores an authenticated SSH session resource as the runtime's active
    // session. Future operations can retrieve it through [activeSession].
    void storeActiveSession(std::unique_ptr<RuntimeResource> session);
    // Returns the active SSH session resource, or nullptr if none is stored.
    RuntimeResource* activeSession() const noexcept;

private:
    friend class SshNativeSession;
    struct Access;
    explicit LoopContext(Access* access) : access_(access) {}
    Access* access_;
};

class Operation {
public:
    virtual ~Operation() = default;
    virtual StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) = 0;
    virtual CancelScope cancelScope() const noexcept { return CancelScope::kRequest; }
    virtual void onCancel(LoopContext&) noexcept {}
};

class Clock {
public:
    virtual ~Clock() = default;
    virtual MonoTime now() const noexcept = 0;
};

class SystemClock final : public Clock {
public:
    MonoTime now() const noexcept override;
};

class Poller {
public:
    virtual ~Poller() = default;
    virtual int poll(std::vector<::pollfd>& fds, int timeout_ms) = 0;
};

class SystemPoller final : public Poller {
public:
    int poll(std::vector<::pollfd>& fds, int timeout_ms) override;
};

struct RuntimeLimits {
    size_t max_queued_commands = 1024;
    size_t max_completion_obligations = 1024;
    size_t max_calls_per_turn = 64;
    size_t max_bytes_per_turn = 256 * 1024;
    std::chrono::milliseconds force_close_after{500};
};

struct RuntimeDependencies {
    std::shared_ptr<Clock> clock;
    std::shared_ptr<Poller> poller;
};

struct RequestOptions {
    Priority priority = Priority::kNormal;
    MonoTime deadline = MonoTime::max();
};

enum class SubmitError { kNone = 0, kInvalidOperation, kClosing, kQueueFull };

struct SubmitResult {
    RequestId request_id = 0;
    SubmitError error = SubmitError::kNone;
    explicit operator bool() const noexcept { return request_id != 0; }
};

struct RuntimeEvent {
    RuntimeEventKind kind = RuntimeEventKind::kCompletion;
    RequestId request_id = 0;
    CompletionKind completion = CompletionKind::kSucceeded;
    SessionState session_state = SessionState::kCreated;
    SshError error;
    std::string payload;
};

class WatermarkedBuffer {
public:
    WatermarkedBuffer(size_t low_watermark, size_t high_watermark);
    bool append(const void* data, size_t size);
    size_t consume(void* destination, size_t size);
    void clear() noexcept;
    size_t size() const noexcept { return data_.size() - offset_; }
    bool empty() const noexcept { return size() == 0; }
    bool producerPaused() const noexcept { return producer_paused_; }

private:
    void compact();
    size_t low_watermark_;
    size_t high_watermark_;
    std::vector<unsigned char> data_;
    size_t offset_ = 0;
    bool producer_paused_ = false;
};

// One event-loop thread owns all transport/libssh2 state. Public methods only
// enqueue control messages or drain immutable events and never call libssh2.
class SshNativeSession {
public:
    explicit SshNativeSession(RuntimeLimits limits = {}, RuntimeDependencies dependencies = {});
    ~SshNativeSession();

    SshNativeSession(const SshNativeSession&) = delete;
    SshNativeSession& operator=(const SshNativeSession&) = delete;

    SubmitResult submit(std::unique_ptr<Operation> operation, RequestOptions options = {});
    bool cancel(RequestId request_id);
    bool waitEvent(RuntimeEvent* event, std::chrono::milliseconds timeout);
    SessionState state() const noexcept;
    bool acceptingCommands() const noexcept;
    std::thread::id ownerThreadId() const noexcept;

    // Idempotent. Queued/active requests are cancelled, resources are closed
    // in dependency order, and the owner thread is joined.
    void shutdown();

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

std::shared_ptr<SshNativeSession> createSession();
const char* sessionStateName(SessionState state) noexcept;
const char* completionKindName(CompletionKind kind) noexcept;
const char* submitErrorName(SubmitError error) noexcept;

} // namespace sshnative

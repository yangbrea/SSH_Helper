#include "ssh_runtime.h"

#include <sys/eventfd.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <fcntl.h>
#include <limits>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <system_error>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace sshnative {

namespace {

constexpr size_t kCommandDrainLimit = 64;
constexpr std::chrono::milliseconds kNoProgressDelay{1};
constexpr std::array<Priority, 7> kSchedule{
    Priority::kInteractive, Priority::kInteractive, Priority::kInteractive,
    Priority::kInteractive, Priority::kNormal, Priority::kNormal, Priority::kBulk,
};

SshError makeError(ErrorDomain domain, std::string code, std::string message,
                   int libssh2_code = 0, int system_errno = 0) {
    return {domain, std::move(code), std::move(message), libssh2_code, system_errno};
}

bool validTransition(SessionState from, SessionState to) noexcept {
    if (from == to) return true;
    if (to == SessionState::kClosing) return from != SessionState::kClosed;
    if (to == SessionState::kFailed) {
        return from != SessionState::kClosing && from != SessionState::kClosed;
    }
    switch (from) {
        case SessionState::kCreated: return to == SessionState::kResolving;
        case SessionState::kResolving: return to == SessionState::kConnecting;
        case SessionState::kConnecting:
            return to == SessionState::kProxyNegotiating || to == SessionState::kHandshaking;
        case SessionState::kProxyNegotiating: return to == SessionState::kHandshaking;
        case SessionState::kHandshaking: return to == SessionState::kVerifyingHostKey;
        case SessionState::kVerifyingHostKey: return to == SessionState::kAuthenticating;
        case SessionState::kAuthenticating: return to == SessionState::kReady;
        case SessionState::kClosing: return to == SessionState::kClosed;
        case SessionState::kReady:
        case SessionState::kFailed:
        case SessionState::kClosed:
            return false;
    }
    return false;
}

size_t queueIndex(Priority priority) noexcept {
    return static_cast<size_t>(priority);
}

class WakeupFd {
public:
    WakeupFd() {
        read_fd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        if (read_fd_ >= 0) {
            write_fd_ = read_fd_;
            event_fd_ = true;
            return;
        }

        int fds[2] = {-1, -1};
#if defined(__linux__)
        if (pipe2(fds, O_NONBLOCK | O_CLOEXEC) != 0) {
            throw std::system_error(errno, std::generic_category(), "eventfd/pipe2");
        }
#else
        if (pipe(fds) != 0) {
            throw std::system_error(errno, std::generic_category(), "eventfd/pipe");
        }
        for (int fd : fds) {
            const int flags = fcntl(fd, F_GETFL, 0);
            if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
                const int saved = errno;
                close(fds[0]);
                close(fds[1]);
                throw std::system_error(saved, std::generic_category(), "fcntl");
            }
        }
#endif
        read_fd_ = fds[0];
        write_fd_ = fds[1];
    }

    ~WakeupFd() {
        if (read_fd_ >= 0) close(read_fd_);
        if (write_fd_ >= 0 && write_fd_ != read_fd_) close(write_fd_);
    }

    int fd() const noexcept { return read_fd_; }

    void signal() noexcept {
        if (write_fd_ < 0) return;
        if (event_fd_) {
            uint64_t one = 1;
            (void)write(write_fd_, &one, sizeof(one));
        } else {
            unsigned char one = 1;
            (void)write(write_fd_, &one, sizeof(one));
        }
    }

    void drain() noexcept {
        if (read_fd_ < 0) return;
        if (event_fd_) {
            uint64_t value;
            while (read(read_fd_, &value, sizeof(value)) > 0) {}
        } else {
            std::array<unsigned char, 128> buffer{};
            while (read(read_fd_, buffer.data(), buffer.size()) > 0) {}
        }
    }

private:
    int read_fd_ = -1;
    int write_fd_ = -1;
    bool event_fd_ = false;
};

} // namespace

bool ReadySet::ready(int fd, short events) const noexcept {
    for (const auto& item : items_) {
        if (item.fd == fd && (item.revents & (events | POLLERR | POLLHUP | POLLNVAL)) != 0) {
            return true;
        }
    }
    return false;
}

StepResult StepResult::complete(std::string payload) {
    StepResult result;
    result.kind = StepKind::kComplete;
    result.payload = std::move(payload);
    return result;
}

StepResult StepResult::progress(size_t bytes) {
    StepResult result;
    result.kind = StepKind::kProgress;
    result.bytes = bytes;
    return result;
}

StepResult StepResult::noProgress() { return {}; }

StepResult StepResult::waitIo(IoInterest interest) {
    StepResult result;
    result.kind = StepKind::kWaitIo;
    result.interest = std::move(interest);
    return result;
}

StepResult StepResult::waitTimer(MonoTime wake_at) {
    StepResult result;
    result.kind = StepKind::kWaitTimer;
    result.wake_at = wake_at;
    return result;
}

StepResult StepResult::failed(SshError error) {
    StepResult result;
    result.kind = StepKind::kFailed;
    result.error = std::move(error);
    return result;
}

MonoTime SystemClock::now() const noexcept { return MonoClock::now(); }

int SystemPoller::poll(std::vector<::pollfd>& fds, int timeout_ms) {
    return ::poll(fds.data(), static_cast<nfds_t>(fds.size()), timeout_ms);
}

WatermarkedBuffer::WatermarkedBuffer(size_t low_watermark, size_t high_watermark)
    : low_watermark_(low_watermark), high_watermark_(high_watermark) {
    if (low_watermark > high_watermark || high_watermark == 0) {
        throw std::invalid_argument("invalid watermarks");
    }
}

bool WatermarkedBuffer::append(const void* data, size_t size) {
    if (size == 0) return true;
    if (data == nullptr || size > high_watermark_ - std::min(high_watermark_, this->size())) {
        producer_paused_ = true;
        return false;
    }
    const auto* bytes = static_cast<const unsigned char*>(data);
    data_.insert(data_.end(), bytes, bytes + size);
    if (this->size() >= high_watermark_) producer_paused_ = true;
    return true;
}

size_t WatermarkedBuffer::consume(void* destination, size_t requested) {
    const size_t amount = std::min(requested, size());
    if (amount != 0 && destination != nullptr) {
        std::memcpy(destination, data_.data() + offset_, amount);
    }
    offset_ += amount;
    if (producer_paused_ && size() <= low_watermark_) producer_paused_ = false;
    compact();
    return amount;
}

void WatermarkedBuffer::clear() noexcept {
    data_.clear();
    offset_ = 0;
    producer_paused_ = false;
}

void WatermarkedBuffer::compact() {
    if (offset_ == data_.size()) {
        data_.clear();
        offset_ = 0;
    } else if (offset_ > 4096 && offset_ * 2 > data_.size()) {
        data_.erase(data_.begin(), data_.begin() + static_cast<std::ptrdiff_t>(offset_));
        offset_ = 0;
    }
}

struct LoopContext::Access {
    virtual ~Access() = default;
    virtual SessionState getState() const noexcept = 0;
    virtual void setState(SessionState next) = 0;
    virtual void add(std::unique_ptr<RuntimeResource> resource) = 0;
    virtual ResourceId storeIndexed(std::unique_ptr<RuntimeResource> resource) = 0;
    virtual RuntimeResource* getIndexed(ResourceId id, ResourceKind kind) noexcept = 0;
    virtual void releaseIndexed(ResourceId id) noexcept = 0;
    virtual void storeTransport(int fd) = 0;
    virtual int takeTransport() = 0;
    virtual void storeSession(std::unique_ptr<RuntimeResource> session) = 0;
    virtual RuntimeResource* getActiveSession() noexcept = 0;
    virtual void storeChannel(std::unique_ptr<RuntimeResource> channel) = 0;
    virtual RuntimeResource* getActiveChannel() noexcept = 0;
    virtual void clearChannel() noexcept = 0;
};

SessionState LoopContext::state() const noexcept { return access_->getState(); }
void LoopContext::transition(SessionState next) { access_->setState(next); }
void LoopContext::addResource(std::unique_ptr<RuntimeResource> resource) {
    if (!resource) throw std::invalid_argument("resource is null");
    access_->add(std::move(resource));
}

ResourceId LoopContext::storeIndexedResource(std::unique_ptr<RuntimeResource> resource) {
    if (!resource) throw std::invalid_argument("indexed resource is null");
    return access_->storeIndexed(std::move(resource));
}

RuntimeResource* LoopContext::indexedResource(ResourceId id, ResourceKind kind) const noexcept {
    return access_->getIndexed(id, kind);
}

void LoopContext::releaseIndexedResource(ResourceId id) noexcept {
    access_->releaseIndexed(id);
}

void LoopContext::storeTransportFd(int fd) {
    if (fd < 0) throw std::invalid_argument("transport fd must not be negative");
    access_->storeTransport(fd);
}

int LoopContext::takeTransportFd() { return access_->takeTransport(); }

void LoopContext::storeActiveSession(std::unique_ptr<RuntimeResource> session) {
    if (!session) throw std::invalid_argument("active session resource is null");
    access_->storeSession(std::move(session));
}

RuntimeResource* LoopContext::activeSession() const noexcept {
    return access_->getActiveSession();
}

void LoopContext::storeActiveChannel(std::unique_ptr<RuntimeResource> channel) {
    if (!channel) throw std::invalid_argument("active channel resource is null");
    access_->storeChannel(std::move(channel));
}

RuntimeResource* LoopContext::activeChannel() const noexcept {
    return access_->getActiveChannel();
}

void LoopContext::clearActiveChannel() noexcept {
    access_->clearChannel();
}

class SshNativeSession::Impl final : public LoopContext::Access {
public:
    Impl(RuntimeLimits limits, RuntimeDependencies dependencies)
        : limits_(limits),
          clock_(dependencies.clock ? std::move(dependencies.clock) : std::make_shared<SystemClock>()),
          poller_(dependencies.poller ? std::move(dependencies.poller) : std::make_shared<SystemPoller>()),
          context_(this) {
        if (limits_.max_queued_commands == 0 || limits_.max_completion_obligations == 0 ||
            limits_.max_calls_per_turn == 0 || limits_.max_bytes_per_turn == 0) {
            throw std::invalid_argument("runtime limits must be non-zero");
        }
        thread_ = std::thread([this] { loop(); });
    }

    ~Impl() override {
        shutdown();
    }

    SubmitResult submit(std::unique_ptr<Operation> operation, RequestOptions options) {
        if (!operation) return {0, SubmitError::kInvalidOperation};
        std::lock_guard<std::mutex> lock(control_mutex_);
        if (!accepting_.load(std::memory_order_acquire)) return {0, SubmitError::kClosing};
        if (commands_.size() >= limits_.max_queued_commands ||
            completion_obligations_.load(std::memory_order_acquire) >=
                limits_.max_completion_obligations) {
            return {0, SubmitError::kQueueFull};
        }

        RequestId id = next_request_id_++;
        if (next_request_id_ == 0) next_request_id_ = 1;
        while (id == 0 || known_requests_.count(id) != 0) {
            id = next_request_id_++;
            if (next_request_id_ == 0) next_request_id_ = 1;
        }
        commands_.push_back({id, std::move(operation), options});
        known_requests_.insert(id);
        completion_obligations_.fetch_add(1, std::memory_order_release);
        wakeup_.signal();
        return {id, SubmitError::kNone};
    }

    bool cancel(RequestId request_id) {
        if (request_id == 0) return false;
        std::lock_guard<std::mutex> lock(control_mutex_);
        if (known_requests_.count(request_id) == 0) return false;
        const bool inserted = cancellations_.insert(request_id).second;
        if (inserted) wakeup_.signal();
        return inserted;
    }

    bool waitEvent(RuntimeEvent* event, std::chrono::milliseconds timeout) {
        if (event == nullptr) return false;
        std::unique_lock<std::mutex> lock(event_mutex_);
        if (timeout.count() < 0) {
            event_cv_.wait(lock, [this] { return !events_.empty() || loop_exited_; });
        } else if (!event_cv_.wait_for(lock, timeout,
                                      [this] { return !events_.empty() || loop_exited_; })) {
            return false;
        }
        if (events_.empty()) return false;
        *event = std::move(events_.front());
        events_.pop_front();
        if (event->kind == RuntimeEventKind::kCompletion) {
            completion_obligations_.fetch_sub(1, std::memory_order_release);
        }
        return true;
    }

    SessionState getState() const noexcept override {
        return static_cast<SessionState>(state_.load(std::memory_order_acquire));
    }

    std::thread::id ownerThreadId() const noexcept {
        std::lock_guard<std::mutex> lock(owner_mutex_);
        return owner_thread_id_;
    }

    bool acceptingCommands() const noexcept { return accepting_.load(std::memory_order_acquire); }

    void shutdown() {
        accepting_.store(false, std::memory_order_release);
        close_requested_.store(true, std::memory_order_release);
        wakeup_.signal();
        if (thread_.joinable() && std::this_thread::get_id() != ownerThreadId()) {
            thread_.join();
        }
    }

    void setState(SessionState next) override {
        assertOwner();
        const SessionState current = getState();
        if (!validTransition(current, next)) {
            throw std::logic_error(std::string("invalid session transition: ") +
                                   sessionStateName(current) + " -> " + sessionStateName(next));
        }
        if (current == next) return;
        state_.store(static_cast<int>(next), std::memory_order_release);
        pushStateEvent(next);
    }

    void add(std::unique_ptr<RuntimeResource> resource) override {
        assertOwner();
        resources_.push_back(std::move(resource));
    }

    ResourceId storeIndexed(std::unique_ptr<RuntimeResource> resource) override {
        assertOwner();
        if (!resource) throw std::invalid_argument("indexed resource is null");
        const ResourceId id = next_resource_id_++;
        RuntimeResource* pointer = resource.get();
        resources_.push_back(std::move(resource));
        indexed_resources_.emplace(id, pointer);
        return id;
    }

    RuntimeResource* getIndexed(ResourceId id, ResourceKind kind) noexcept override {
        assertOwner();
        const auto found = indexed_resources_.find(id);
        if (found == indexed_resources_.end() || found->second == nullptr ||
            found->second->kind() != kind) {
            return nullptr;
        }
        return found->second;
    }

    void releaseIndexed(ResourceId id) noexcept override {
        assertOwner();
        const auto found = indexed_resources_.find(id);
        if (found == indexed_resources_.end()) return;
        if (found->second != nullptr) found->second->forceClose();
        indexed_resources_.erase(found);
    }

    void storeTransport(int fd) override {
        assertOwner();
        if (fd < 0) throw std::invalid_argument("transport fd must not be negative");
        if (pending_transport_fd_ >= 0) {
            close(pending_transport_fd_);
        }
        pending_transport_fd_ = fd;
    }

    int takeTransport() override {
        assertOwner();
        const int fd = pending_transport_fd_;
        pending_transport_fd_ = -1;
        return fd;
    }

    void storeSession(std::unique_ptr<RuntimeResource> session) override {
        assertOwner();
        if (!session) throw std::invalid_argument("active session resource is null");
        if (active_session_ != nullptr) {
            throw std::logic_error("active SSH session already exists");
        }
        resources_.push_back(std::move(session));
        active_session_ = resources_.back().get();
    }

    RuntimeResource* getActiveSession() noexcept override {
        return active_session_;
    }

    void storeChannel(std::unique_ptr<RuntimeResource> channel) override {
        assertOwner();
        if (!channel) throw std::invalid_argument("active channel resource is null");
        if (active_channel_ != nullptr) {
            throw std::logic_error("active SSH channel already exists");
        }
        resources_.push_back(std::move(channel));
        active_channel_ = resources_.back().get();
    }

    RuntimeResource* getActiveChannel() noexcept override {
        return active_channel_;
    }

    void clearChannel() noexcept override {
        assertOwner();
        active_channel_ = nullptr;
    }

private:
    struct Command {
        RequestId id;
        std::unique_ptr<Operation> operation;
        RequestOptions options;
    };

    enum class RecordState { kRunnable, kWaitingIo, kWaitingTimer };

    struct Record {
        RequestId id;
        std::unique_ptr<Operation> operation;
        RequestOptions options;
        RecordState state = RecordState::kRunnable;
        IoInterest interest;
        MonoTime wake_at = MonoTime::max();
        unsigned empty_interest_count = 0;
        bool enqueued = false;
    };

    void assertOwner() const {
        if (std::this_thread::get_id() != ownerThreadId()) {
            throw std::logic_error("SSH runtime state accessed outside owner thread");
        }
    }

    void loop() noexcept {
        {
            std::lock_guard<std::mutex> lock(owner_mutex_);
            owner_thread_id_ = std::this_thread::get_id();
        }
        try {
            while (true) {
                wakeup_.drain();
                drainCommands();
                processCancellations();

                const MonoTime now = clock_->now();
                expireDeadlines(now);

                if (close_requested_.load(std::memory_order_acquire)) {
                    if (!closing_started_) beginClose(now);
                    if (closeResources(now)) break;
                } else {
                    wakeWaiters(now);
                    runOperations(now);
                }

                std::vector<::pollfd> poll_fds = buildPollFds();
                const int result = poller_->poll(poll_fds, pollTimeout(clock_->now()));
                if (result < 0) {
                    if (errno == EINTR) continue;
                    fatal_error_ = makeError(ErrorDomain::kSystem, "poll_failed",
                                             std::strerror(errno), 0, errno);
                    accepting_.store(false, std::memory_order_release);
                    close_requested_.store(true, std::memory_order_release);
                    continue;
                }
                ready_.items_.clear();
                for (const auto& pfd : poll_fds) {
                    if (pfd.revents != 0) ready_.items_.push_back({pfd.fd, pfd.revents});
                }
            }
        } catch (const std::exception& exception) {
            fatal_error_ = makeError(ErrorDomain::kInternal, "runtime_exception", exception.what());
            emergencyClose();
        } catch (...) {
            fatal_error_ = makeError(ErrorDomain::kInternal, "runtime_exception",
                                     "unknown event-loop exception");
            emergencyClose();
        }

        accepting_.store(false, std::memory_order_release);
        if (getState() != SessionState::kClosed) {
            state_.store(static_cast<int>(SessionState::kClosed), std::memory_order_release);
            pushStateEvent(SessionState::kClosed);
        }
        {
            std::lock_guard<std::mutex> lock(event_mutex_);
            loop_exited_ = true;
        }
        event_cv_.notify_all();
    }

    void drainCommands() {
        std::deque<Command> local;
        {
            std::lock_guard<std::mutex> lock(control_mutex_);
            const size_t count = std::min(kCommandDrainLimit, commands_.size());
            for (size_t i = 0; i < count; ++i) {
                local.push_back(std::move(commands_.front()));
                commands_.pop_front();
            }
        }
        for (auto& command : local) {
            if (isCancelled(command.id) || close_requested_.load(std::memory_order_acquire)) {
                complete(command.id, CompletionKind::kCancelled, {}, cancelledError());
                continue;
            }
            auto record = std::make_unique<Record>();
            record->id = command.id;
            record->operation = std::move(command.operation);
            record->options = command.options;
            const RequestId id = record->id;
            active_.emplace(id, std::move(record));
            enqueue(id);
        }
    }

    bool isCancelled(RequestId id) {
        std::lock_guard<std::mutex> lock(control_mutex_);
        return cancellations_.count(id) != 0;
    }

    void processCancellations() {
        std::vector<RequestId> ids;
        {
            std::lock_guard<std::mutex> lock(control_mutex_);
            ids.assign(cancellations_.begin(), cancellations_.end());
        }
        for (RequestId id : ids) {
            auto found = active_.find(id);
            if (found == active_.end()) continue;
            const CancelScope scope = found->second->operation->cancelScope();
            found->second->operation->onCancel(context_);
            complete(id, CompletionKind::kCancelled, {}, cancelledError());
            if (scope == CancelScope::kTransport) {
                accepting_.store(false, std::memory_order_release);
                close_requested_.store(true, std::memory_order_release);
            }
        }
    }

    void expireDeadlines(MonoTime now) {
        std::vector<RequestId> expired;
        for (const auto& item : active_) {
            if (item.second->options.deadline != MonoTime::max() &&
                now >= item.second->options.deadline) {
                expired.push_back(item.first);
            }
        }
        for (RequestId id : expired) {
            auto found = active_.find(id);
            if (found == active_.end()) continue;
            const CancelScope scope = found->second->operation->cancelScope();
            found->second->operation->onCancel(context_);
            complete(id, CompletionKind::kFailed, {},
                     makeError(ErrorDomain::kTimeout, "deadline_exceeded", "request deadline exceeded"));
            if (scope == CancelScope::kTransport) {
                accepting_.store(false, std::memory_order_release);
                close_requested_.store(true, std::memory_order_release);
            }
        }
    }

    void wakeWaiters(MonoTime now) {
        for (auto& item : active_) {
            Record& record = *item.second;
            if (record.state == RecordState::kWaitingTimer && now >= record.wake_at) {
                record.state = RecordState::kRunnable;
                enqueue(record.id);
            } else if (record.state == RecordState::kWaitingIo) {
                for (const auto& interest : record.interest) {
                    if (ready_.ready(interest.fd, interest.events)) {
                        record.state = RecordState::kRunnable;
                        enqueue(record.id);
                        break;
                    }
                }
            }
        }
    }

    void runOperations(MonoTime initial_now) {
        size_t calls = 0;
        size_t bytes = 0;
        MonoTime now = initial_now;
        while (!close_requested_.load(std::memory_order_acquire) &&
               calls < limits_.max_calls_per_turn && bytes < limits_.max_bytes_per_turn) {
            const RequestId id = nextRunnable();
            if (id == 0) break;
            auto found = active_.find(id);
            if (found == active_.end()) continue;
            Record& record = *found->second;
            record.enqueued = false;
            if (record.options.deadline != MonoTime::max() && now >= record.options.deadline) {
                record.operation->onCancel(context_);
                complete(id, CompletionKind::kFailed, {},
                         makeError(ErrorDomain::kTimeout, "deadline_exceeded",
                                   "request deadline exceeded"));
                continue;
            }

            StepResult result;
            try {
                result = record.operation->step(context_, ready_, now);
            } catch (const std::exception& exception) {
                result = StepResult::failed(makeError(ErrorDomain::kInternal,
                                                       "operation_exception", exception.what()));
            } catch (...) {
                result = StepResult::failed(makeError(ErrorDomain::kInternal,
                                                       "operation_exception",
                                                       "unknown operation exception"));
            }
            ++calls;
            bytes += result.bytes;
            handleStep(id, std::move(result), now);
            now = clock_->now();
        }
    }

    void handleStep(RequestId id, StepResult result, MonoTime now) {
        auto found = active_.find(id);
        if (found == active_.end()) return;
        Record& record = *found->second;
        switch (result.kind) {
            case StepKind::kComplete:
                complete(id, CompletionKind::kSucceeded, std::move(result.payload), {});
                return;
            case StepKind::kFailed:
                if (!result.error) {
                    result.error = makeError(ErrorDomain::kInternal, "operation_failed",
                                             "operation failed without an error");
                }
                complete(id, CompletionKind::kFailed, {}, std::move(result.error));
                return;
            case StepKind::kProgress:
                record.empty_interest_count = 0;
                record.state = RecordState::kRunnable;
                enqueue(id);
                return;
            case StepKind::kNoProgress:
                record.state = RecordState::kWaitingTimer;
                record.wake_at = now + kNoProgressDelay;
                return;
            case StepKind::kWaitTimer:
                record.empty_interest_count = 0;
                record.state = RecordState::kWaitingTimer;
                record.wake_at = result.wake_at;
                return;
            case StepKind::kWaitIo:
                if (result.interest.empty()) {
                    if (++record.empty_interest_count > 1) {
                        complete(id, CompletionKind::kFailed, {},
                                 makeError(ErrorDomain::kInternal, "empty_io_interest",
                                           "operation returned EAGAIN without poll directions"));
                    } else {
                        record.state = RecordState::kRunnable;
                        enqueue(id);
                    }
                    return;
                }
                record.empty_interest_count = 0;
                record.state = RecordState::kWaitingIo;
                record.interest = std::move(result.interest);
                return;
        }
    }

    void enqueue(RequestId id) {
        const auto found = active_.find(id);
        if (found == active_.end() || found->second->enqueued) return;
        found->second->enqueued = true;
        runnable_[queueIndex(found->second->options.priority)].push_back(id);
    }

    RequestId nextRunnable() {
        for (size_t tries = 0; tries < kSchedule.size(); ++tries) {
            const Priority priority = kSchedule[schedule_cursor_];
            schedule_cursor_ = (schedule_cursor_ + 1) % kSchedule.size();
            auto& queue = runnable_[queueIndex(priority)];
            while (!queue.empty()) {
                const RequestId id = queue.front();
                queue.pop_front();
                const auto found = active_.find(id);
                if (found != active_.end() && found->second->enqueued) return id;
            }
        }
        return 0;
    }

    bool hasRunnable() const {
        for (const auto& queue : runnable_) {
            if (!queue.empty()) return true;
        }
        return false;
    }

    void complete(RequestId id, CompletionKind kind, std::string payload, SshError error) {
        active_.erase(id);
        {
            std::lock_guard<std::mutex> lock(control_mutex_);
            known_requests_.erase(id);
            cancellations_.erase(id);
        }
        RuntimeEvent event;
        event.kind = RuntimeEventKind::kCompletion;
        event.request_id = id;
        event.completion = kind;
        event.error = std::move(error);
        event.payload = std::move(payload);
        pushEvent(std::move(event));
    }

    void beginClose(MonoTime now) {
        closing_started_ = true;
        accepting_.store(false, std::memory_order_release);
        if (getState() != SessionState::kClosing && getState() != SessionState::kClosed) {
            setState(SessionState::kClosing);
        }

        std::deque<Command> queued;
        {
            std::lock_guard<std::mutex> lock(control_mutex_);
            queued.swap(commands_);
        }
        for (auto& command : queued) {
            completeForClose(command.id);
        }

        std::vector<RequestId> active_ids;
        active_ids.reserve(active_.size());
        for (const auto& item : active_) active_ids.push_back(item.first);
        for (RequestId id : active_ids) {
            auto found = active_.find(id);
            if (found == active_.end()) continue;
            found->second->operation->onCancel(context_);
            completeForClose(id);
        }

        closePendingTransport();

        std::stable_sort(resources_.begin(), resources_.end(),
                         [](const auto& left, const auto& right) {
                             return left->kind() < right->kind();
                         });
        force_close_at_ = now + limits_.force_close_after;
    }

    void completeForClose(RequestId id) {
        if (fatal_error_) {
            complete(id, CompletionKind::kFailed, {}, *fatal_error_);
        } else {
            complete(id, CompletionKind::kCancelled, {}, cancelledError());
        }
    }

    bool closeResources(MonoTime now) {
        if (now >= force_close_at_) {
            for (size_t i = resources_.size(); i > close_index_; --i) {
                resources_[i - 1]->forceClose();
            }
            close_index_ = resources_.size();
        }
        if (close_index_ >= resources_.size()) {
            active_session_ = nullptr;
            active_channel_ = nullptr;
            return true;
        }

        StepResult result;
        try {
            result = resources_[close_index_]->closeStep(ready_, now);
        } catch (...) {
            resources_[close_index_]->forceClose();
            ++close_index_;
            return close_index_ >= resources_.size();
        }
        closing_interest_.clear();
        closing_wake_at_ = MonoTime::max();
        switch (result.kind) {
            case StepKind::kComplete:
                ++close_index_;
                break;
            case StepKind::kFailed:
                resources_[close_index_]->forceClose();
                ++close_index_;
                break;
            case StepKind::kWaitIo:
                closing_interest_ = std::move(result.interest);
                if (closing_interest_.empty()) closing_wake_at_ = now + kNoProgressDelay;
                break;
            case StepKind::kWaitTimer:
                closing_wake_at_ = result.wake_at;
                break;
            case StepKind::kProgress:
            case StepKind::kNoProgress:
                closing_wake_at_ = now + kNoProgressDelay;
                break;
        }
        return close_index_ >= resources_.size();
    }

    void closePendingTransport() noexcept {
        if (pending_transport_fd_ >= 0) {
            close(pending_transport_fd_);
            pending_transport_fd_ = -1;
        }
    }

    void emergencyClose() noexcept {
        accepting_.store(false, std::memory_order_release);
        std::deque<Command> queued;
        {
            std::lock_guard<std::mutex> lock(control_mutex_);
            queued.swap(commands_);
        }
        for (auto& command : queued) completeForClose(command.id);
        std::vector<RequestId> ids;
        for (const auto& item : active_) ids.push_back(item.first);
        for (RequestId id : ids) completeForClose(id);
        for (auto iterator = resources_.rbegin(); iterator != resources_.rend(); ++iterator) {
            (*iterator)->forceClose();
        }
        closePendingTransport();
    }

    std::vector<::pollfd> buildPollFds() const {
        std::vector<::pollfd> result;
        result.push_back({wakeup_.fd(), POLLIN, 0});
        auto addInterest = [&result](const PollInterest& interest) {
            if (interest.fd < 0 || interest.events == 0) return;
            for (auto& pfd : result) {
                if (pfd.fd == interest.fd) {
                    pfd.events = static_cast<short>(pfd.events | interest.events);
                    return;
                }
            }
            result.push_back({interest.fd, interest.events, 0});
        };
        if (closing_started_) {
            for (const auto& interest : closing_interest_) addInterest(interest);
        } else {
            for (const auto& item : active_) {
                if (item.second->state == RecordState::kWaitingIo) {
                    for (const auto& interest : item.second->interest) addInterest(interest);
                }
            }
        }
        return result;
    }

    int pollTimeout(MonoTime now) const {
        if (close_requested_.load(std::memory_order_acquire) && !closing_started_) return 0;
        if (!closing_started_ && hasRunnable()) return 0;
        MonoTime earliest = MonoTime::max();
        if (closing_started_) {
            earliest = std::min(force_close_at_, closing_wake_at_);
            if (closing_interest_.empty() && close_index_ < resources_.size() &&
                closing_wake_at_ == MonoTime::max()) return 0;
        } else {
            for (const auto& item : active_) {
                earliest = std::min(earliest, item.second->options.deadline);
                if (item.second->state == RecordState::kWaitingTimer) {
                    earliest = std::min(earliest, item.second->wake_at);
                }
            }
        }
        if (earliest == MonoTime::max()) return -1;
        if (earliest <= now) return 0;
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(earliest - now);
        if (remaining.count() >= std::numeric_limits<int>::max()) return std::numeric_limits<int>::max();
        return std::max(1, static_cast<int>(remaining.count()));
    }

    void pushStateEvent(SessionState state) {
        RuntimeEvent event;
        event.kind = RuntimeEventKind::kSessionStateChanged;
        event.session_state = state;
        std::lock_guard<std::mutex> lock(event_mutex_);
        events_.erase(std::remove_if(events_.begin(), events_.end(), [](const RuntimeEvent& old) {
                          return old.kind == RuntimeEventKind::kSessionStateChanged;
                      }), events_.end());
        events_.push_back(std::move(event));
        event_cv_.notify_all();
    }

    void pushEvent(RuntimeEvent event) {
        {
            std::lock_guard<std::mutex> lock(event_mutex_);
            events_.push_back(std::move(event));
        }
        event_cv_.notify_all();
    }

    static SshError cancelledError() {
        return makeError(ErrorDomain::kCancelled, "cancelled", "request cancelled");
    }

    RuntimeLimits limits_;
    std::shared_ptr<Clock> clock_;
    std::shared_ptr<Poller> poller_;
    WakeupFd wakeup_;
    LoopContext context_;

    mutable std::mutex owner_mutex_;
    std::thread::id owner_thread_id_;
    std::thread thread_;

    std::atomic<bool> accepting_{true};
    std::atomic<bool> close_requested_{false};
    std::atomic<int> state_{static_cast<int>(SessionState::kCreated)};
    std::atomic<size_t> completion_obligations_{0};

    std::mutex control_mutex_;
    std::deque<Command> commands_;
    std::unordered_set<RequestId> known_requests_;
    std::unordered_set<RequestId> cancellations_;
    RequestId next_request_id_ = 1;

    std::mutex event_mutex_;
    std::condition_variable event_cv_;
    std::deque<RuntimeEvent> events_;
    bool loop_exited_ = false;

    std::unordered_map<RequestId, std::unique_ptr<Record>> active_;
    std::array<std::deque<RequestId>, 3> runnable_;
    size_t schedule_cursor_ = 0;
    ReadySet ready_;

    std::vector<std::unique_ptr<RuntimeResource>> resources_;
    std::unordered_map<ResourceId, RuntimeResource*> indexed_resources_;
    ResourceId next_resource_id_ = 1;
    RuntimeResource* active_session_ = nullptr;
    RuntimeResource* active_channel_ = nullptr;
    int pending_transport_fd_ = -1;
    bool closing_started_ = false;
    size_t close_index_ = 0;
    MonoTime force_close_at_ = MonoTime::max();
    IoInterest closing_interest_;
    MonoTime closing_wake_at_ = MonoTime::max();
    std::optional<SshError> fatal_error_;
};

SshNativeSession::SshNativeSession(RuntimeLimits limits, RuntimeDependencies dependencies)
    : impl_(std::make_unique<Impl>(limits, std::move(dependencies))) {}

SshNativeSession::~SshNativeSession() = default;

SubmitResult SshNativeSession::submit(std::unique_ptr<Operation> operation, RequestOptions options) {
    return impl_->submit(std::move(operation), options);
}

bool SshNativeSession::cancel(RequestId request_id) { return impl_->cancel(request_id); }

bool SshNativeSession::waitEvent(RuntimeEvent* event, std::chrono::milliseconds timeout) {
    return impl_->waitEvent(event, timeout);
}

SessionState SshNativeSession::state() const noexcept { return impl_->getState(); }
bool SshNativeSession::acceptingCommands() const noexcept { return impl_->acceptingCommands(); }
std::thread::id SshNativeSession::ownerThreadId() const noexcept { return impl_->ownerThreadId(); }
void SshNativeSession::shutdown() { impl_->shutdown(); }

std::shared_ptr<SshNativeSession> createSession() { return std::make_shared<SshNativeSession>(); }

const char* sessionStateName(SessionState state) noexcept {
    switch (state) {
        case SessionState::kCreated: return "created";
        case SessionState::kResolving: return "resolving";
        case SessionState::kConnecting: return "connecting";
        case SessionState::kProxyNegotiating: return "proxy_negotiating";
        case SessionState::kHandshaking: return "handshaking";
        case SessionState::kVerifyingHostKey: return "verifying_host_key";
        case SessionState::kAuthenticating: return "authenticating";
        case SessionState::kReady: return "ready";
        case SessionState::kClosing: return "closing";
        case SessionState::kClosed: return "closed";
        case SessionState::kFailed: return "failed";
    }
    return "unknown";
}

const char* completionKindName(CompletionKind kind) noexcept {
    switch (kind) {
        case CompletionKind::kSucceeded: return "succeeded";
        case CompletionKind::kFailed: return "failed";
        case CompletionKind::kCancelled: return "cancelled";
    }
    return "unknown";
}

const char* submitErrorName(SubmitError error) noexcept {
    switch (error) {
        case SubmitError::kNone: return "none";
        case SubmitError::kInvalidOperation: return "invalid_operation";
        case SubmitError::kClosing: return "closing";
        case SubmitError::kQueueFull: return "queue_full";
    }
    return "unknown";
}

} // namespace sshnative

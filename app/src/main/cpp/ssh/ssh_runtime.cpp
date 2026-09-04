#include "ssh_runtime.h"

#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

#include <cerrno>
#include <limits>
#include <system_error>
#include <utility>

namespace sshnative {

namespace {

constexpr size_t kWakeBufferSize = 64;

} // namespace

SshNativeSession::SshNativeSession() {
    int fds[2] = {-1, -1};
    if (pipe(fds) != 0) {
        throw std::system_error(errno, std::generic_category(), "pipe");
    }
    wake_read_ = fds[0];
    wake_write_ = fds[1];

    // Both pipe ends are non-blocking: writes never block producers and the
    // event loop can drain all pending wake bytes without blocking on read.
    const int read_flags = fcntl(wake_read_, F_GETFL, 0);
    const int write_flags = fcntl(wake_write_, F_GETFL, 0);
    if (read_flags < 0 || write_flags < 0 ||
        fcntl(wake_read_, F_SETFL, read_flags | O_NONBLOCK) != 0 ||
        fcntl(wake_write_, F_SETFL, write_flags | O_NONBLOCK) != 0) {
        close(wake_read_);
        close(wake_write_);
        wake_read_ = -1;
        wake_write_ = -1;
        throw std::system_error(errno, std::generic_category(), "fcntl");
    }

    thread_ = std::thread([this] { loop(); });
}

SshNativeSession::~SshNativeSession() {
    shutdown();
    if (wake_read_ != -1) {
        close(wake_read_);
        wake_read_ = -1;
    }
    if (wake_write_ != -1) {
        close(wake_write_);
        wake_write_ = -1;
    }
}

bool SshNativeSession::post(std::function<void()> task) {
    return postRequest(std::move(task)) != 0;
}

SshNativeSession::RequestId SshNativeSession::postRequest(std::function<void()> task) {
    if (!task) return 0;
    auto command = std::make_shared<Command>();
    command->task = std::move(task);

    RequestId id;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (shutting_down_) return 0;
        id = next_request_id_;
        next_request_id_ = next_request_id_ == std::numeric_limits<RequestId>::max()
            ? 1
            : next_request_id_ + 1;
        command->id = id;
        queue_.push_back(command);
        pending_.emplace(id, command);
    }
    wake();
    return id;
}

bool SshNativeSession::cancel(RequestId id) {
    if (id == 0) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    const auto found = pending_.find(id);
    if (found == pending_.end()) return false;
    return !found->second->canceled.exchange(true);
}

void SshNativeSession::shutdown() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (shutting_down_) return;
        shutting_down_ = true;
    }
    wake();
    if (thread_.joinable()) {
        thread_.join();
    }
}

void SshNativeSession::wake() {
    if (wake_write_ == -1) return;
    char byte = 1;
    const ssize_t written = write(wake_write_, &byte, sizeof(byte));
    (void)written; // EAGAIN is fine; a byte is already pending.
}

void SshNativeSession::loop() {
    while (true) {
        struct pollfd pfd {};
        pfd.fd = wake_read_;
        pfd.events = POLLIN;
        const int poll_result = poll(&pfd, 1, -1);
        if (poll_result < 0) {
            if (errno == EINTR) continue;
            break;
        }

        char buffer[kWakeBufferSize];
        while (read(wake_read_, buffer, sizeof(buffer)) > 0) {
            // Drain all wake bytes.
        }

        bool should_stop = false;
        std::deque<std::shared_ptr<Command>> local;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            local.swap(queue_);
            if (local.empty() && shutting_down_) {
                should_stop = true;
            }
        }

        for (auto& command : local) {
            bool run = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                pending_.erase(command->id);
                run = !command->canceled.load();
            }
            if (run) {
                command->task();
            }
        }

        if (should_stop) break;

        // Re-check after executing the batch so a shutdown arriving during a
        // task is observed promptly.
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (queue_.empty() && shutting_down_) {
                break;
            }
        }
    }
}

std::shared_ptr<SshNativeSession> createSession() {
    return std::make_shared<SshNativeSession>();
}

} // namespace sshnative

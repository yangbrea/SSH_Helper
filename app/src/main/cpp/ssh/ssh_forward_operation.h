#pragma once

#include "ssh_runtime.h"

#include <libssh2.h>

#include <cstdint>
#include <netdb.h>
#include <string>

namespace sshnative {

enum class ForwardKind { kLocal, kRemote };

// Foreground operation: creates a local listening socket and starts a
// background accept loop. Completion payload is "id=<group>\nport=<actual>".
class StartLocalForwardOperation final : public Operation {
public:
    StartLocalForwardOperation(
        std::string bind_address,
        uint16_t listen_port,
        std::string target_host,
        uint16_t target_port);
    ~StartLocalForwardOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void closeListenFd() noexcept;

    int listen_fd_ = -1;
    int actual_port_ = 0;
    std::string bind_address_;
    uint16_t listen_port_ = 0;
    std::string target_host_;
    uint16_t target_port_ = 0;
    uint64_t group_id_ = 0;
    bool started_ = false;
};

// Foreground operation: creates a libssh2 remote forward listener and starts a
// background accept loop. Completion payload is "id=<group>\nport=<actual>".
class StartRemoteForwardOperation final : public Operation {
public:
    StartRemoteForwardOperation(
        std::string bind_address,
        uint16_t listen_port,
        std::string target_host,
        uint16_t target_port);
    ~StartRemoteForwardOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    std::string bind_address_;
    uint16_t listen_port_ = 0;
    std::string target_host_;
    uint16_t target_port_ = 0;
    LIBSSH2_LISTENER* listener_ = nullptr;
    int bound_port_ = 0;
    uint64_t group_id_ = 0;
    bool started_ = false;
};

// Foreground operation: cancels all background operations in one forwarding
// group. The group id is the value returned by a Start*ForwardOperation.
class CloseForwardOperation final : public Operation {
public:
    explicit CloseForwardOperation(uint64_t group_id) : group_id_(group_id) {}

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    uint64_t group_id_ = 0;
};

// Background accept loop for a local (-L) listener.
class LocalForwardAcceptOperation final : public Operation {
public:
    LocalForwardAcceptOperation(
        int listen_fd,
        LIBSSH2_SESSION* session,
        int ssh_fd,
        std::string target_host,
        uint16_t target_port);
    ~LocalForwardAcceptOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
    void onCancel(LoopContext&) noexcept override;
    bool isBackground() const noexcept override { return true; }

private:
    int listen_fd_ = -1;
    LIBSSH2_SESSION* session_ = nullptr;
    int ssh_fd_ = -1;
    std::string target_host_;
    uint16_t target_port_ = 0;
};

// Background accept loop for a remote (-R) listener.
class RemoteForwardAcceptOperation final : public Operation {
public:
    RemoteForwardAcceptOperation(
        LIBSSH2_LISTENER* listener,
        LIBSSH2_SESSION* session,
        int ssh_fd,
        std::string target_host,
        uint16_t target_port);
    ~RemoteForwardAcceptOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
    void onCancel(LoopContext&) noexcept override;
    bool isBackground() const noexcept override { return true; }

private:
    LIBSSH2_LISTENER* listener_ = nullptr;
    LIBSSH2_SESSION* session_ = nullptr;
    int ssh_fd_ = -1;
    std::string target_host_;
    uint16_t target_port_ = 0;
};

// Background bidirectional pump between a local TCP socket and one SSH channel.
// It is also responsible for opening the missing side:
//   local forward: channel is opened to the SSH target;
//   remote forward: local TCP socket is connected to the requested target.
class ForwardConnectionOperation final : public Operation {
public:
    ForwardConnectionOperation(
        ForwardKind kind,
        LIBSSH2_SESSION* session,
        int ssh_fd,
        int local_fd,
        LIBSSH2_CHANNEL* channel,
        std::string target_host,
        uint16_t target_port);
    ~ForwardConnectionOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
    void onCancel(LoopContext&) noexcept override;
    bool isBackground() const noexcept override { return true; }

private:
    enum class Phase { kOpening, kConnecting, kPumping, kClosing, kDone };

    void cleanup() noexcept;
    void closeLocalSocket() noexcept;
    void closeChannel() noexcept;
    void closeConnectResources() noexcept;

    StepResult openingStep();
    StepResult connectingStep(const ReadySet& ready, MonoTime now);
    StepResult pumpingStep();
    StepResult finishClosing();

    bool done() const noexcept {
        return local_read_eof_ && channel_read_eof_ &&
            channel_eof_sent_ && local_write_closed_;
    }

    ForwardKind kind_;
    LIBSSH2_SESSION* session_ = nullptr;
    int ssh_fd_ = -1;
    int local_fd_ = -1;
    LIBSSH2_CHANNEL* channel_ = nullptr;
    std::string target_host_;
    uint16_t target_port_ = 0;
    Phase phase_;

    // Non-blocking connect state for remote forward targets.
    addrinfo* connect_addresses_ = nullptr;
    addrinfo* connect_current_ = nullptr;
    int connect_fd_ = -1;
    bool connect_pending_ = false;
    MonoTime connect_deadline_ = MonoTime::max();

    // Data pump state.
    std::string to_channel_;
    std::string to_local_;
    bool local_read_eof_ = false;
    bool channel_read_eof_ = false;
    bool channel_eof_sent_ = false;
    bool local_write_closed_ = false;
    bool close_started_ = false;
    bool close_done_ = false;
};

} // namespace sshnative

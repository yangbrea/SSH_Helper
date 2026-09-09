#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <chrono>
#include <cstdint>
#include <memory>
#include <string>

namespace sshnative {

// Transport context shared by a jump session and the nested target session that
// runs over a direct-tcpip channel on the jump session. The channel is owned by
// the target-side SshSessionResource and is freed before the jump session.
struct JumpTunnelTransport {
    LIBSSH2_SESSION* jump_session = nullptr;
    LIBSSH2_CHANNEL* channel = nullptr;
    int jump_fd = -1;
    // Only set while keyboard-interactive authentication is in progress. The
    // pointed-to password belongs to the active operation.
    const std::string* keyboard_password = nullptr;
};

// An authenticated libssh2 session owned by the runtime event loop. It is stored
// as the active SSH session and remains usable for later exec/shell/SFTP
// requests without reconnecting or re-authenticating. For jump routes the same
// resource is reused for the target session, with a custom transport over a
// direct-tcpip channel on the jump session.
class SshSessionResource final : public RuntimeResource {
public:
    SshSessionResource(
        std::unique_ptr<Libssh2Session> session,
        int fd,
        ResourceKind kind = ResourceKind::kLibssh2Session,
        bool owns_fd = true);
    ~SshSessionResource() override;

    ResourceKind kind() const noexcept override { return kind_; }
    StepResult closeStep(const ReadySet& ready, MonoTime now) override;
    void forceClose() noexcept override;

    Libssh2Session* session() const noexcept { return session_.get(); }
    int fd() const noexcept { return fd_; }
    bool ownsFd() const noexcept { return owns_fd_; }
    void setResourceKind(ResourceKind kind) noexcept { kind_ = kind; }
    void setJumpTunnel(std::shared_ptr<JumpTunnelTransport> tunnel);
    std::shared_ptr<JumpTunnelTransport> jumpTunnel() const noexcept { return tunnel_; }

private:
    void closeTunnelChannel() noexcept;

    std::unique_ptr<Libssh2Session> session_;
    int fd_ = -1;
    bool owns_fd_ = true;
    ResourceKind kind_ = ResourceKind::kLibssh2Session;
    std::shared_ptr<JumpTunnelTransport> tunnel_;
};

// Consumes the runtime's pending transport socket, performs a nonblocking SSH
// handshake, verifies the expected host key when provided, authenticates, and
// stores the resulting [SshSessionResource] as the active SSH session.
class OpenAuthenticatedSessionOperation final : public Operation {
public:
    OpenAuthenticatedSessionOperation(
        std::string username,
        std::string password,
        std::string private_key,
        std::string passphrase,
        std::string expected_fingerprint = {},
        bool store_as_jump = false);
    ~OpenAuthenticatedSessionOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    bool usePrivateKey() const noexcept { return !private_key_.empty(); }

    int fd_ = -1;
    std::unique_ptr<Libssh2Session> session_;
    std::string username_;
    std::string password_;
    std::string private_key_;
    std::string passphrase_;
    std::string expected_fingerprint_;
    bool store_as_jump_ = false;

    bool handshake_started_ = false;
    bool handshake_done_ = false;
    bool hostkey_checked_ = false;
    bool auth_method_decided_ = false;
    bool use_keyboard_interactive_ = false;
    bool auth_started_ = false;
    bool auth_done_ = false;
};

// Consumes a handshaked-but-unauthenticated pending SSH session, performs
// password or in-memory private-key authentication on the same connection, and
// stores it as the active SSH session.
class AuthenticatePendingSessionOperation final : public Operation {
public:
    AuthenticatePendingSessionOperation(
        std::string username,
        std::string password,
        std::string private_key,
        std::string passphrase,
        bool store_as_jump = false);
    ~AuthenticatePendingSessionOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    bool usePrivateKey() const noexcept { return !private_key_.empty(); }

    std::unique_ptr<RuntimeResource> pending_;
    std::string username_;
    std::string password_;
    std::string private_key_;
    std::string passphrase_;
    bool store_as_jump_ = false;
    bool auth_method_decided_ = false;
    bool use_keyboard_interactive_ = false;
    bool auth_started_ = false;
    bool auth_done_ = false;
};

// Closes and clears a pending unauthenticated SSH session after a rejected,
// changed, cancelled, or timed-out host-key decision.
class AbortPendingSessionOperation final : public Operation {
public:
    AbortPendingSessionOperation() = default;
    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
};

// Runs one exec on the active authenticated SSH session. The session remains
// open so callers can submit further commands on the same transport.
class PersistentExecOperation final : public Operation {
public:
    explicit PersistentExecOperation(
        std::string command,
        size_t max_output_bytes = 1024 * 1024);
    ~PersistentExecOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    std::string command_;
    size_t max_output_bytes_ = 1024 * 1024;
    LIBSSH2_CHANNEL* channel_ = nullptr;
    bool exec_started_ = false;
    bool close_started_ = false;
    bool eof_sent_ = false;
    bool wait_closed_done_ = false;
    bool output_limit_hit_ = false;
    std::string output_;
    std::string stderr_;
};

} // namespace sshnative

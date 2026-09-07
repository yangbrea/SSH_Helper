#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"
#include "ssh_persistent_session.h"

#include <cstdint>
#include <memory>
#include <string>

namespace sshnative {

// Opens a direct-tcpip channel on the authenticated jump session and then runs
// a nested target SSH session over that channel using custom send/recv I/O
// callbacks. On success the target session is stored as the runtime's active
// session while the jump session remains as the route's auxiliary session.
class OpenJumpTargetSessionOperation final : public Operation {
public:
    OpenJumpTargetSessionOperation(
        std::string target_host,
        uint16_t target_port,
        std::string username,
        std::string password,
        std::string private_key,
        std::string passphrase,
        std::string expected_fingerprint = {});
    ~OpenJumpTargetSessionOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
    CancelScope cancelScope() const noexcept override { return CancelScope::kTransport; }

private:
    bool usePrivateKey() const noexcept { return !private_key_.empty(); }

    std::string target_host_;
    uint16_t target_port_ = 0;
    std::string username_;
    std::string password_;
    std::string private_key_;
    std::string passphrase_;
    std::string expected_fingerprint_;

    int jump_fd_ = -1;
    LIBSSH2_CHANNEL* channel_ = nullptr;
    std::shared_ptr<JumpTunnelTransport> tunnel_;
    std::unique_ptr<Libssh2Session> session_;
    bool channel_started_ = false;
    bool handshake_started_ = false;
    bool handshake_done_ = false;
    bool hostkey_checked_ = false;
    bool auth_method_decided_ = false;
    bool use_keyboard_interactive_ = false;
    bool auth_started_ = false;
    bool auth_done_ = false;
};

// Opens a temporary direct-tcpip tunnel on the jump session and performs only
// the nested target SSH handshake, returning the target host-key payload without
// authenticating. Used by Kotlin for the pre-auth host-key gate.
class OpenJumpTargetHandshakeOperation final : public Operation {
public:
    OpenJumpTargetHandshakeOperation(
        std::string target_host,
        uint16_t target_port);
    ~OpenJumpTargetHandshakeOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
    CancelScope cancelScope() const noexcept override { return CancelScope::kTransport; }

private:
    std::string target_host_;
    uint16_t target_port_ = 0;

    int jump_fd_ = -1;
    LIBSSH2_CHANNEL* channel_ = nullptr;
    std::shared_ptr<JumpTunnelTransport> tunnel_;
    std::unique_ptr<Libssh2Session> session_;
    bool handshake_done_ = false;
};

} // namespace sshnative

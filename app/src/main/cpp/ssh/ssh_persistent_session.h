#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <chrono>
#include <cstdint>
#include <memory>
#include <string>

namespace sshnative {

// An authenticated libssh2 session owned by the runtime event loop. It is stored
// as the active SSH session and remains usable for later exec/shell/SFTP
// requests without reconnecting or re-authenticating.
class SshSessionResource final : public RuntimeResource {
public:
    SshSessionResource(std::unique_ptr<Libssh2Session> session, int fd);
    ~SshSessionResource() override;

    ResourceKind kind() const noexcept override { return ResourceKind::kLibssh2Session; }
    StepResult closeStep(const ReadySet& ready, MonoTime now) override;
    void forceClose() noexcept override;

    Libssh2Session* session() const noexcept { return session_.get(); }
    int fd() const noexcept { return fd_; }

private:
    std::unique_ptr<Libssh2Session> session_;
    int fd_ = -1;
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
        std::string expected_fingerprint = {});
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

    bool handshake_started_ = false;
    bool handshake_done_ = false;
    bool hostkey_checked_ = false;
    bool auth_method_decided_ = false;
    bool use_keyboard_interactive_ = false;
    bool auth_started_ = false;
    bool auth_done_ = false;
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

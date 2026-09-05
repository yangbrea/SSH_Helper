#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <chrono>
#include <cstdint>
#include <netdb.h>
#include <string>

namespace sshnative {

// Full direct transport operation: nonblocking TCP connect + SSH handshake +
// plain password auth + exec stdout capture. DNS is resolved in the
// constructor; all socket/SSH work happens on the runtime owner thread.
class TcpPasswordExecOperation final : public Operation {
public:
    TcpPasswordExecOperation(
        std::string host,
        uint16_t port,
        std::string username,
        std::string password,
        std::string command,
        std::chrono::milliseconds connect_timeout,
        size_t max_output_bytes = 1024 * 1024,
        std::string expected_fingerprint = {});
    ~TcpPasswordExecOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void advanceToNextAddress() noexcept;

    std::string host_;
    uint16_t port_ = 0;
    std::string username_;
    std::string password_;
    std::string command_;
    std::string expected_fingerprint_;
    MonoTime deadline_ = MonoTime::max();

    addrinfo* addresses_ = nullptr;
    addrinfo* current_ = nullptr;
    int fd_ = -1;
    bool connect_pending_ = false;
    bool connected_ = false;

    Libssh2Session session_;
    LIBSSH2_CHANNEL* channel_ = nullptr;
    bool handshake_started_ = false;
    bool handshake_done_ = false;
    bool hostkey_checked_ = false;
    bool auth_started_ = false;
    bool auth_method_decided_ = false;
    bool use_keyboard_interactive_ = false;
    bool auth_done_ = false;
    bool exec_started_ = false;
    bool close_started_ = false;
    std::string output_;
    std::string stderr_;
    size_t max_output_bytes_ = 1024 * 1024;
    bool output_limit_hit_ = false;
};

// Full direct transport operation with in-memory private-key authentication.
class TcpPrivateKeyExecOperation final : public Operation {
public:
    TcpPrivateKeyExecOperation(
        std::string host,
        uint16_t port,
        std::string username,
        std::string private_key,
        std::string passphrase,
        std::string command,
        std::chrono::milliseconds connect_timeout,
        size_t max_output_bytes = 1024 * 1024,
        std::string expected_fingerprint = {});
    ~TcpPrivateKeyExecOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void advanceToNextAddress() noexcept;

    std::string host_;
    uint16_t port_ = 0;
    std::string username_;
    std::string private_key_;
    std::string passphrase_;
    std::string command_;
    std::string expected_fingerprint_;
    MonoTime deadline_ = MonoTime::max();

    addrinfo* addresses_ = nullptr;
    addrinfo* current_ = nullptr;
    int fd_ = -1;
    bool connect_pending_ = false;
    bool connected_ = false;

    Libssh2Session session_;
    LIBSSH2_CHANNEL* channel_ = nullptr;
    bool handshake_started_ = false;
    bool handshake_done_ = false;
    bool hostkey_checked_ = false;
    bool auth_started_ = false;
    bool auth_done_ = false;
    bool exec_started_ = false;
    bool close_started_ = false;
    std::string output_;
    std::string stderr_;
    size_t max_output_bytes_ = 1024 * 1024;
    bool output_limit_hit_ = false;
};

} // namespace sshnative

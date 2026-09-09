#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <chrono>
#include <cstdint>
#include <netdb.h>
#include <string>

namespace sshnative {

// Connects to host:port, performs a nonblocking SSH handshake, reads the host
// key and completes with "fingerprint=...\nkeyType=..." without authenticating.
class TcpHandshakeOperation final : public Operation {
public:
    TcpHandshakeOperation(
        std::string host,
        uint16_t port,
        std::chrono::milliseconds connect_timeout,
        bool hold_pending = false);
    ~TcpHandshakeOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void advanceToNextAddress() noexcept;

    std::string host_;
    uint16_t port_ = 0;
    MonoTime deadline_ = MonoTime::max();

    addrinfo* addresses_ = nullptr;
    addrinfo* current_ = nullptr;
    int fd_ = -1;
    bool connect_pending_ = false;
    bool connected_ = false;

    Libssh2Session session_;
    bool handshake_started_ = false;
    bool handshake_done_ = false;
    bool hold_pending_ = false;
};

} // namespace sshnative

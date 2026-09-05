#pragma once

#include "ssh_runtime.h"

#include <chrono>
#include <cstdint>
#include <netdb.h>
#include <string>

namespace sshnative {

// Nonblocking TCP connect operation. DNS resolution happens in the constructor
// (caller/producer thread); socket creation, connect and poll continuation run
// on the runtime owner thread. On success the completion payload is "connected".
//
// This is the first runtime piece for Step 5 direct transport. It currently owns
// the connected fd only until the operation completes; later Steps will move the
// fd into a persistent runtime resource so handshake/auth can continue on the
// same transport.
class TcpConnectOperation final : public Operation {
public:
    TcpConnectOperation(
        std::string host,
        uint16_t port,
        std::chrono::milliseconds timeout);
    ~TcpConnectOperation() override;

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
    bool done_ = false;
};

} // namespace sshnative

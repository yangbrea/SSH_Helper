#pragma once

#include "ssh_runtime.h"

#include <chrono>
#include <cstdint>
#include <netdb.h>
#include <string>

namespace sshnative {

// Nonblocking HTTP CONNECT proxy operation. It resolves and connects to the
// proxy, sends CONNECT for target_host:target_port, and reads the proxy
// response header. On success the completion payload is "connected".
//
// This is an incremental Step 5 proxy operation; the connected fd is currently
// owned only until the operation completes.
class HttpProxyConnectOperation final : public Operation {
public:
    HttpProxyConnectOperation(
        std::string proxy_host,
        uint16_t proxy_port,
        std::string target_host,
        uint16_t target_port,
        std::string username,
        std::string password,
        std::chrono::milliseconds timeout);
    ~HttpProxyConnectOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void closeFdAndAdvance() noexcept;

    std::string proxy_host_;
    uint16_t proxy_port_ = 0;
    std::string target_host_;
    uint16_t target_port_ = 0;
    std::string username_;
    std::string password_;
    MonoTime deadline_ = MonoTime::max();

    addrinfo* addresses_ = nullptr;
    addrinfo* current_ = nullptr;
    int fd_ = -1;
    bool connect_pending_ = false;
    bool connected_ = false;

    std::string request_;
    size_t request_offset_ = 0;
    bool request_sent_ = false;
    std::string response_;
};

} // namespace sshnative

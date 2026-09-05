#pragma once

#include "ssh_runtime.h"

#include <chrono>
#include <cstdint>
#include <netdb.h>
#include <string>

namespace sshnative {

// Nonblocking SOCKS5 CONNECT operation. Supports no-auth and username/password
// auth. On success the completion payload is "connected".
class Socks5ProxyConnectOperation final : public Operation {
public:
    Socks5ProxyConnectOperation(
        std::string proxy_host,
        uint16_t proxy_port,
        std::string target_host,
        uint16_t target_port,
        std::string username,
        std::string password,
        std::chrono::milliseconds timeout);
    ~Socks5ProxyConnectOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void closeFdAndAdvance() noexcept;
    bool writePending(const ReadySet& ready, MonoTime now);
    bool readExactly(size_t needed, const ReadySet& ready, MonoTime now);

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

    enum class Phase {
        kConnect,
        kWriteGreeting,
        kReadMethod,
        kWriteAuth,
        kReadAuthReply,
        kWriteConnect,
        kReadReplyHead,
        kReadAddress,
        kDone,
    };
    Phase phase_ = Phase::kConnect;

    std::string write_data_;
    size_t write_offset_ = 0;
    std::string read_data_;
    size_t read_need_ = 0;
    uint8_t selected_method_ = 0;
    uint8_t reply_atyp_ = 0;
};

} // namespace sshnative

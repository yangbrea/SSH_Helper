#pragma once

#include <chrono>
#include <cstdint>
#include <string>

namespace sshnative {

enum class ProxyType {
    kNone,
    kHttpConnect,
    kSocks5,
};

struct ProxySettings {
    ProxyType type = ProxyType::kNone;
    std::string host;
    uint16_t port = 0;
    std::string username;
    std::string password;
};

// Establish a transport to host:port, optionally through an HTTP CONNECT or
// SOCKS5 proxy. On success the caller owns the returned non-blocking fd which
// is ready for the SSH handshake. Proxy credentials are never logged.
int connectTransport(
    const std::string& host,
    uint16_t port,
    const ProxySettings& proxy,
    std::chrono::milliseconds timeout);

} // namespace sshnative

#pragma once

#include <chrono>
#include <cstdint>
#include <string>

namespace sshnative {

// Perform an HTTP CONNECT handshake over an already-connected proxy socket.
// The socket must be non-blocking. On success the caller keeps ownership of the
// same fd, now ready for the SSH handshake. Proxy credentials are never logged.
void httpConnectTunnel(
    int fd,
    const std::string& target_host,
    uint16_t target_port,
    const std::string& proxy_username,
    const std::string& proxy_password,
    std::chrono::milliseconds timeout);

} // namespace sshnative

#pragma once

#include <chrono>
#include <cstdint>
#include <string>

namespace sshnative {

// Perform a SOCKS5 CONNECT handshake over an already-connected proxy socket.
// The socket must be non-blocking. Username/password are used only when either
// is non-empty. On success the caller keeps ownership of the same fd, ready for
// the SSH handshake. Credentials are never logged.
void socks5ConnectTunnel(
    int fd,
    const std::string& target_host,
    uint16_t target_port,
    const std::string& username,
    const std::string& password,
    std::chrono::milliseconds timeout);

} // namespace sshnative

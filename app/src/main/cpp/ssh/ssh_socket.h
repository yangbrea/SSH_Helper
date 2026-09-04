#pragma once

#include <chrono>
#include <cstdint>
#include <string>

namespace sshnative {

// Establish a non-blocking TCP connection to host:port. On success the caller
// owns the returned non-blocking fd. Throws std::runtime_error with a stable
// message on resolution/connect/timeout failure.
int connectTcp(
    const std::string& host,
    uint16_t port,
    std::chrono::milliseconds timeout);

void closeFd(int fd);

} // namespace sshnative

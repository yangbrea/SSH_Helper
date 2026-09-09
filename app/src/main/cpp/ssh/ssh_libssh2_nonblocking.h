#pragma once

#include "ssh_runtime.h"

#include <libssh2.h>

#include <cstddef>
#include <string>

namespace sshnative {

enum class Libssh2CallKind { kSucceeded = 0, kWouldBlock, kFailed };

struct Libssh2CallResult {
    Libssh2CallKind kind = Libssh2CallKind::kSucceeded;
    ssize_t value = 0;
    void* pointer = nullptr;
    IoInterest interest;
    SshError error;
};

// These adapters must be called immediately after the corresponding libssh2
// call. In particular, EAGAIN directions and last-error text are session-local
// transient state and are copied before any other libssh2 API is invoked.
Libssh2CallResult classifyLibssh2Int(
    LIBSSH2_SESSION* session,
    int socket_fd,
    int result,
    ErrorDomain domain,
    const char* operation_code);

Libssh2CallResult classifyLibssh2Count(
    LIBSSH2_SESSION* session,
    int socket_fd,
    ssize_t result,
    ErrorDomain domain,
    const char* operation_code);

Libssh2CallResult classifyLibssh2Pointer(
    LIBSSH2_SESSION* session,
    int socket_fd,
    void* result,
    ErrorDomain domain,
    const char* operation_code);

IoInterest libssh2PollInterest(LIBSSH2_SESSION* session, int socket_fd);

} // namespace sshnative

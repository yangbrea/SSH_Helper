#include "ssh_libssh2_nonblocking.h"

#include <poll.h>

#include <string>

namespace sshnative {

namespace {

SshError copyLastError(LIBSSH2_SESSION* session, ErrorDomain domain,
                       const char* operation_code, int fallback_code) {
    char* message = nullptr;
    int message_length = 0;
    const int code = libssh2_session_last_error(session, &message, &message_length, 0);
    std::string copied_message;
    if (message != nullptr && message_length > 0) {
        copied_message.assign(message, static_cast<size_t>(message_length));
    } else {
        copied_message = std::string(operation_code) + " failed";
    }
    SshError error;
    error.domain = domain;
    error.code = std::string(operation_code) + "_failed";
    error.message = std::move(copied_message);
    error.libssh2_code = code != 0 ? code : fallback_code;
    return error;
}

Libssh2CallResult wouldBlock(LIBSSH2_SESSION* session, int socket_fd) {
    Libssh2CallResult translated;
    translated.kind = Libssh2CallKind::kWouldBlock;
    translated.value = LIBSSH2_ERROR_EAGAIN;
    translated.interest = libssh2PollInterest(session, socket_fd);
    return translated;
}

} // namespace

IoInterest libssh2PollInterest(LIBSSH2_SESSION* session, int socket_fd) {
    IoInterest result;
    if (session == nullptr || socket_fd < 0) return result;
    const int directions = libssh2_session_block_directions(session);
    short events = 0;
    if ((directions & LIBSSH2_SESSION_BLOCK_INBOUND) != 0) events |= POLLIN;
    if ((directions & LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) events |= POLLOUT;
    if (events != 0) result.push_back({socket_fd, events});
    return result;
}

Libssh2CallResult classifyLibssh2Int(LIBSSH2_SESSION* session, int socket_fd,
                                     int result, ErrorDomain domain,
                                     const char* operation_code) {
    if (result == LIBSSH2_ERROR_EAGAIN) return wouldBlock(session, socket_fd);
    Libssh2CallResult translated;
    translated.value = result;
    if (result >= 0) return translated;
    translated.kind = Libssh2CallKind::kFailed;
    translated.error = copyLastError(session, domain, operation_code, result);
    return translated;
}

Libssh2CallResult classifyLibssh2Count(LIBSSH2_SESSION* session, int socket_fd,
                                       ssize_t result, ErrorDomain domain,
                                       const char* operation_code) {
    if (result == LIBSSH2_ERROR_EAGAIN) return wouldBlock(session, socket_fd);
    Libssh2CallResult translated;
    translated.value = result;
    if (result >= 0) return translated;
    translated.kind = Libssh2CallKind::kFailed;
    translated.error = copyLastError(session, domain, operation_code,
                                     static_cast<int>(result));
    return translated;
}

Libssh2CallResult classifyLibssh2Pointer(LIBSSH2_SESSION* session, int socket_fd,
                                         void* result, ErrorDomain domain,
                                         const char* operation_code) {
    Libssh2CallResult translated;
    translated.pointer = result;
    if (result != nullptr) return translated;
    const int last_code = libssh2_session_last_errno(session);
    if (last_code == LIBSSH2_ERROR_EAGAIN) return wouldBlock(session, socket_fd);
    translated.kind = Libssh2CallKind::kFailed;
    translated.error = copyLastError(session, domain, operation_code, last_code);
    return translated;
}

} // namespace sshnative

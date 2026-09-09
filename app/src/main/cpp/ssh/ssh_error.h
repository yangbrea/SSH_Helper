#pragma once

#include <string>

namespace sshnative {

// Stable error domain used at the JNI boundary. Later steps expand this into
// the full error mapping for transport, handshake, auth, channel and SFTP.
enum class ErrorDomain {
    kNone = 0,
    kInvalidHandle,
    kSystem,
    kDns,
    kProxy,
    kSshHandshake,
    kHostKey,
    kAuth,
    kChannel,
    kSftp,
    kTimeout,
    kCancelled,
    kInternal,
};

struct SshError {
    ErrorDomain domain = ErrorDomain::kNone;
    std::string code;
    std::string message;
    int libssh2_code = 0;
    int system_errno = 0;

    explicit operator bool() const noexcept { return domain != ErrorDomain::kNone; }
};

const char* errorDomainName(ErrorDomain domain);

} // namespace sshnative

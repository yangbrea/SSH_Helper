#pragma once

namespace sshnative {

// Stable error domain used at the JNI boundary. Later steps expand this into
// the full error mapping for transport, handshake, auth, channel and SFTP.
enum class ErrorDomain {
    kNone = 0,
    kInvalidHandle,
    kInternal,
};

const char* errorDomainName(ErrorDomain domain);

} // namespace sshnative

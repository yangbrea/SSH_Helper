#include "ssh_error.h"

namespace sshnative {

const char* errorDomainName(ErrorDomain domain) {
    switch (domain) {
        case ErrorDomain::kNone:
            return "none";
        case ErrorDomain::kInvalidHandle:
            return "invalid_handle";
        case ErrorDomain::kSystem:
            return "system";
        case ErrorDomain::kDns:
            return "dns";
        case ErrorDomain::kProxy:
            return "proxy";
        case ErrorDomain::kSshHandshake:
            return "ssh_handshake";
        case ErrorDomain::kHostKey:
            return "host_key";
        case ErrorDomain::kAuth:
            return "auth";
        case ErrorDomain::kChannel:
            return "channel";
        case ErrorDomain::kSftp:
            return "sftp";
        case ErrorDomain::kTimeout:
            return "timeout";
        case ErrorDomain::kCancelled:
            return "cancelled";
        case ErrorDomain::kInternal:
            return "internal";
    }
    return "unknown";
}

} // namespace sshnative

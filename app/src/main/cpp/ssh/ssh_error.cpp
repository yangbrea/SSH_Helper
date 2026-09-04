#include "ssh_error.h"

namespace sshnative {

const char* errorDomainName(ErrorDomain domain) {
    switch (domain) {
        case ErrorDomain::kNone:
            return "none";
        case ErrorDomain::kInvalidHandle:
            return "invalid_handle";
        case ErrorDomain::kInternal:
            return "internal";
    }
    return "unknown";
}

} // namespace sshnative

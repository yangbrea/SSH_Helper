#include "ssh_runtime.h"

namespace sshnative {

std::shared_ptr<SshNativeSession> createSession() {
    return std::make_shared<SshNativeSession>();
}

} // namespace sshnative

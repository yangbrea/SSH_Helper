#pragma once

#include <memory>

namespace sshnative {

// The owner object for one native SSH transport. Later migration steps will
// extend this struct with the non-blocking session runtime, command queue and
// libssh2 session state. For now it only validates registry lifecycle.
struct SshNativeSession {
    bool closed = false;
};

std::shared_ptr<SshNativeSession> createSession();

} // namespace sshnative

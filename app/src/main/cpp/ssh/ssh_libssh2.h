#pragma once

#include <libssh2.h>

#include <string>

namespace sshnative {

// RAII wrapper around one LIBSSH2_SESSION. This does not own a socket or an
// event loop yet; it establishes that libssh2 session lifecycle is usable from
// the native SSH runtime and will be extended with handshake/auth/channel APIs.
class Libssh2Session {
public:
    Libssh2Session();
    ~Libssh2Session();

    Libssh2Session(const Libssh2Session&) = delete;
    Libssh2Session& operator=(const Libssh2Session&) = delete;

    LIBSSH2_SESSION* get() const { return session_; }

    static std::string libraryVersion();

private:
    LIBSSH2_SESSION* session_ = nullptr;
};

} // namespace sshnative

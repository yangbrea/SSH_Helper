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

    void setBlocking(bool enabled);

    // Perform a blocking SSH transport handshake on an already-connected socket.
    // The caller keeps ownership of the socket.
    void handshake(int socket_fd);

    // Perform blocking password authentication. Returns true on success.
    bool passwordAuth(const std::string& username, const std::string& password);

    static std::string libraryVersion();

private:
    LIBSSH2_SESSION* session_ = nullptr;
};

} // namespace sshnative

#pragma once

#include <libssh2.h>

#include <string>
#include <vector>

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

    // Authenticate with a password using plain "password" when the server offers
    // it, otherwise fall back to keyboard-interactive once when that is the only
    // available method. This avoids retrying a bad password on a second method.
    bool passwordOrKeyboardAuth(
        const std::string& username,
        const std::string& password);

    // Perform blocking public-key authentication from an in-memory OpenSSH/PEM
    // private key. Passphrase may be empty for unencrypted keys.
    bool publicKeyAuth(
        const std::string& username,
        const std::string& private_key,
        const std::string& passphrase);

    // After a successful handshake, return the server host key blob. When
    // type_out is non-null it receives the libssh2 host-key type constant.
    std::vector<uint8_t> hostKey(int* type_out = nullptr);

    // Execute a command in blocking mode and return its exit status.
    int execCommand(const std::string& command, std::string& output);

    static std::string libraryVersion();

private:
    LIBSSH2_SESSION* session_ = nullptr;
};

} // namespace sshnative

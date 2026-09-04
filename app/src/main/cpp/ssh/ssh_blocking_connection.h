#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "ssh_libssh2.h"

namespace sshnative {

struct BlockingSshHostKey {
    std::string type;
    std::string fingerprint;
    std::string key_base64;
};

// A blocking direct TCP + libssh2 connection that has completed the SSH
// handshake but has not authenticated yet. This lets the Kotlin/JNI layer
// verify the server host key before sending any user credentials.
//
// The object owns the connected socket and the LIBSSH2_SESSION. close() is
// idempotent and the destructor performs the same cleanup.
class BlockingSshConnection {
public:
    static std::shared_ptr<BlockingSshConnection> openDirect(
        const std::string& host,
        uint16_t port);

    ~BlockingSshConnection();

    BlockingSshConnection(const BlockingSshConnection&) = delete;
    BlockingSshConnection& operator=(const BlockingSshConnection&) = delete;

    // Returns type/fingerprint/Base64 for the server host key received during
    // the handshake. May be called only after openDirect succeeded.
    BlockingSshHostKey hostKeyDetails();

    // Authenticate and execute one command. On success returns the remote exit
    // status and fills output. Throws std::runtime_error on auth/exec failure.
    int execPassword(
        const std::string& username,
        const std::string& password,
        const std::string& command,
        std::string& output);

    int execPublicKey(
        const std::string& username,
        const std::string& private_key,
        const std::string& passphrase,
        const std::string& command,
        std::string& output);

    void close();

private:
    BlockingSshConnection() = default;

    int fd_ = -1;
    Libssh2Session session_;
};

} // namespace sshnative

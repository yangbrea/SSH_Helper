#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

namespace sshnative {

// Takes ownership of an already-connected socket fd and performs a nonblocking
// libssh2 handshake on the runtime owner thread. On success the completion
// payload is "fingerprint=<SHA256:...>\nkeyType=<type>".
//
// This is an incremental Step 5 operation: it proves real libssh2 calls can be
// driven through the Step 4 event loop with EAGAIN/poll continuations before
// DNS/TCP/proxy/auth operations are added.
class Libssh2HandshakeOperation final : public Operation {
public:
    explicit Libssh2HandshakeOperation(int socket_fd);
    ~Libssh2HandshakeOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    int fd_ = -1;
    Libssh2Session session_;
    bool handshake_started_ = false;
};

// Performs a nonblocking handshake followed by plain password authentication on
// an already-connected socket. Completion payload is "auth=ok" on success.
class Libssh2PasswordAuthOperation final : public Operation {
public:
    Libssh2PasswordAuthOperation(
        int socket_fd,
        std::string username,
        std::string password);
    ~Libssh2PasswordAuthOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    int fd_ = -1;
    Libssh2Session session_;
    bool handshake_started_ = false;
    bool handshake_done_ = false;
    bool auth_started_ = false;
    std::string username_;
    std::string password_;
};

} // namespace sshnative

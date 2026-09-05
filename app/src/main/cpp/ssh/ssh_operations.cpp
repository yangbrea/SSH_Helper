#include "ssh_operations.h"

#include <libssh2.h>

#include <stdexcept>
#include <string>
#include <vector>

#include "ssh_error.h"
#include "ssh_hostkey.h"
#include "ssh_libssh2_nonblocking.h"
#include "ssh_socket.h"

namespace sshnative {

Libssh2HandshakeOperation::Libssh2HandshakeOperation(int socket_fd)
    : fd_(socket_fd) {
    if (fd_ < 0) throw std::invalid_argument("socket fd must not be negative");
}

Libssh2HandshakeOperation::~Libssh2HandshakeOperation() {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
}

StepResult Libssh2HandshakeOperation::step(
    LoopContext&,
    const ReadySet&,
    MonoTime) {
    if (!handshake_started_) {
        session_.setBlocking(false);
        handshake_started_ = true;
    }

    const int result = libssh2_session_handshake(session_.get(), fd_);
    Libssh2CallResult translated = classifyLibssh2Int(
        session_.get(), fd_, result, ErrorDomain::kSshHandshake,
        "session_handshake");
    switch (translated.kind) {
        case Libssh2CallKind::kWouldBlock:
            return StepResult::waitIo(std::move(translated.interest));
        case Libssh2CallKind::kFailed:
            return StepResult::failed(std::move(translated.error));
        case Libssh2CallKind::kSucceeded:
            break;
    }

    int key_type = 0;
    const std::vector<uint8_t> blob = session_.hostKey(&key_type);
    if (blob.empty()) {
        SshError error;
        error.domain = ErrorDomain::kHostKey;
        error.code = "host_key_unavailable";
        error.message = "handshake succeeded but host key was not available";
        return StepResult::failed(std::move(error));
    }
    const std::string fingerprint = hostKeySha256Fingerprint(blob);
    const std::string key_type_name = hostKeyTypeName(key_type);
    return StepResult::complete(
        "fingerprint=" + fingerprint + "\nkeyType=" + key_type_name);
}

} // namespace sshnative

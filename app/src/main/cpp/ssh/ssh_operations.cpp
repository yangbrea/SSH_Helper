#include "ssh_operations.h"

#include <libssh2.h>

#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "ssh_error.h"
#include "ssh_hostkey.h"
#include "ssh_libssh2_nonblocking.h"
#include "ssh_persistent_session.h"
#include "ssh_socket.h"

namespace sshnative {

Libssh2HandshakeOperation::Libssh2HandshakeOperation(int socket_fd, bool hold_pending)
    : fd_(socket_fd), hold_pending_(hold_pending) {
    if (fd_ < -1) throw std::invalid_argument("socket fd must not be negative");
}

Libssh2HandshakeOperation::~Libssh2HandshakeOperation() {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
}

StepResult Libssh2HandshakeOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    if (fd_ < 0) {
        fd_ = context.takeTransportFd();
        if (fd_ < 0) {
            return StepResult::failed(SshError{
                ErrorDomain::kInternal, "no_transport_fd",
                "operation requires an established transport socket"});
        }
    }
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
    if (hold_pending_) {
        auto held_session = std::make_unique<Libssh2Session>(std::move(session_));
        auto resource = std::make_unique<SshSessionResource>(
            std::move(held_session), fd_, ResourceKind::kLibssh2Session,
            /*owns_fd=*/true);
        fd_ = -1;
        context.storePendingSession(std::move(resource));
    }
    return StepResult::complete(
        "fingerprint=" + fingerprint +
        "\nkeyType=" + key_type_name +
        "\nkeyBase64=" + hostKeyBase64(blob));
}

Libssh2PasswordAuthOperation::Libssh2PasswordAuthOperation(
    int socket_fd,
    std::string username,
    std::string password)
    : fd_(socket_fd),
      username_(std::move(username)),
      password_(std::move(password)) {
    if (fd_ < 0) throw std::invalid_argument("socket fd must not be negative");
    if (username_.empty() || password_.empty()) {
        throw std::invalid_argument("username/password must not be empty");
    }
}

Libssh2PasswordAuthOperation::~Libssh2PasswordAuthOperation() {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
}

StepResult Libssh2PasswordAuthOperation::step(
    LoopContext&,
    const ReadySet&,
    MonoTime) {
    if (!handshake_started_) {
        session_.setBlocking(false);
        handshake_started_ = true;
    }

    if (!handshake_done_) {
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
                handshake_done_ = true;
                break;
        }
    }

    if (!auth_started_) {
        auth_started_ = true;
    }
    const int result = libssh2_userauth_password(
        session_.get(), username_.c_str(), password_.c_str());
    Libssh2CallResult translated = classifyLibssh2Int(
        session_.get(), fd_, result, ErrorDomain::kAuth, "userauth_password");
    switch (translated.kind) {
        case Libssh2CallKind::kWouldBlock:
            return StepResult::waitIo(std::move(translated.interest));
        case Libssh2CallKind::kFailed:
            return StepResult::failed(std::move(translated.error));
        case Libssh2CallKind::kSucceeded:
            return StepResult::complete("auth=ok");
    }
    return StepResult::failed(SshError{ErrorDomain::kInternal, "unreachable",
                                       "unexpected auth operation state"});
}

Libssh2PrivateKeyAuthOperation::Libssh2PrivateKeyAuthOperation(
    int socket_fd,
    std::string username,
    std::string private_key,
    std::string passphrase)
    : fd_(socket_fd),
      username_(std::move(username)),
      private_key_(std::move(private_key)),
      passphrase_(std::move(passphrase)) {
    if (fd_ < 0) throw std::invalid_argument("socket fd must not be negative");
    if (username_.empty() || private_key_.empty()) {
        throw std::invalid_argument("username/private key must not be empty");
    }
}

Libssh2PrivateKeyAuthOperation::~Libssh2PrivateKeyAuthOperation() {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
}

StepResult Libssh2PrivateKeyAuthOperation::step(
    LoopContext&,
    const ReadySet&,
    MonoTime) {
    if (!handshake_started_) {
        session_.setBlocking(false);
        handshake_started_ = true;
    }

    if (!handshake_done_) {
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
                handshake_done_ = true;
                break;
        }
    }

    if (!auth_started_) {
        auth_started_ = true;
    }
    const int result = libssh2_userauth_publickey_frommemory(
        session_.get(),
        username_.c_str(),
        static_cast<unsigned int>(username_.size()),
        nullptr,
        0,
        private_key_.data(),
        private_key_.size(),
        passphrase_.empty() ? nullptr : passphrase_.c_str());
    Libssh2CallResult translated = classifyLibssh2Int(
        session_.get(), fd_, result, ErrorDomain::kAuth,
        "userauth_publickey_frommemory");
    switch (translated.kind) {
        case Libssh2CallKind::kWouldBlock:
            return StepResult::waitIo(std::move(translated.interest));
        case Libssh2CallKind::kFailed:
            return StepResult::failed(std::move(translated.error));
        case Libssh2CallKind::kSucceeded:
            return StepResult::complete("auth=ok");
    }
    return StepResult::failed(SshError{ErrorDomain::kInternal, "unreachable",
                                       "unexpected auth operation state"});
}

Libssh2PasswordExecOperation::Libssh2PasswordExecOperation(
    int socket_fd,
    std::string username,
    std::string password,
    std::string command)
    : fd_(socket_fd),
      username_(std::move(username)),
      password_(std::move(password)),
      command_(std::move(command)) {
    if (fd_ < 0) throw std::invalid_argument("socket fd must not be negative");
    if (username_.empty() || password_.empty() || command_.empty()) {
        throw std::invalid_argument("username/password/command must not be empty");
    }
}

Libssh2PasswordExecOperation::~Libssh2PasswordExecOperation() {
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
}

StepResult Libssh2PasswordExecOperation::step(
    LoopContext&,
    const ReadySet&,
    MonoTime) {
    if (!handshake_started_) {
        session_.setBlocking(false);
        handshake_started_ = true;
    }

    if (!handshake_done_) {
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
                handshake_done_ = true;
                break;
        }
    }

    if (!auth_done_) {
        if (!auth_started_) auth_started_ = true;
        const int result = libssh2_userauth_password(
            session_.get(), username_.c_str(), password_.c_str());
        Libssh2CallResult translated = classifyLibssh2Int(
            session_.get(), fd_, result, ErrorDomain::kAuth,
            "userauth_password");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                auth_done_ = true;
                break;
        }
    }

    if (channel_ == nullptr) {
        Libssh2CallResult translated = classifyLibssh2Pointer(
            session_.get(), fd_, libssh2_channel_open_session(session_.get()),
            ErrorDomain::kChannel, "channel_open_session");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                channel_ = static_cast<LIBSSH2_CHANNEL*>(translated.pointer);
                break;
        }
    }

    if (!exec_started_) {
        exec_started_ = true;
        const int result = libssh2_channel_exec(
            channel_, command_.c_str());
        Libssh2CallResult translated = classifyLibssh2Int(
            session_.get(), fd_, result, ErrorDomain::kChannel,
            "channel_exec");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                break;
        }
    }

    if (!close_started_) {
        char buffer[4096];
        const ssize_t count = libssh2_channel_read(
            channel_, buffer, sizeof(buffer));
        Libssh2CallResult translated = classifyLibssh2Count(
            session_.get(), fd_, count, ErrorDomain::kChannel,
            "channel_read");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                break;
        }
        if (translated.value > 0) {
            output_.append(buffer, static_cast<size_t>(translated.value));
            return StepResult::progress(static_cast<size_t>(translated.value));
        }
        // In non-blocking mode, zero from channel_read means EOF has been
        // reached for stdout; proceed to close and read the exit status.
        close_started_ = true;
    }

    if (close_started_) {
        const int result = libssh2_channel_close(channel_);
        Libssh2CallResult translated = classifyLibssh2Int(
            session_.get(), fd_, result, ErrorDomain::kChannel,
            "channel_close");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                break;
        }
        const int exit_code = libssh2_channel_get_exit_status(channel_);
        libssh2_channel_free(channel_);
        channel_ = nullptr;
        return StepResult::complete(
            "exit=" + std::to_string(exit_code) + "\n" + output_);
    }

    return StepResult::failed(SshError{ErrorDomain::kInternal, "unreachable",
                                       "unexpected exec operation state"});
}
} // namespace sshnative

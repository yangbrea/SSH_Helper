#include "ssh_direct_operation.h"

#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <system_error>
#include <utility>

#include "ssh_error.h"
#include "ssh_libssh2_nonblocking.h"
#include "ssh_socket.h"

namespace sshnative {

namespace {

void setNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        throw std::system_error(errno, std::generic_category(), "fcntl");
    }
}

} // namespace

TcpPasswordExecOperation::TcpPasswordExecOperation(
    std::string host,
    uint16_t port,
    std::string username,
    std::string password,
    std::string command,
    std::chrono::milliseconds connect_timeout)
    : host_(std::move(host)),
      port_(port),
      username_(std::move(username)),
      password_(std::move(password)),
      command_(std::move(command)),
      deadline_(MonoClock::now() + connect_timeout) {
    if (host_.empty() || username_.empty() || password_.empty() || command_.empty()) {
        throw std::invalid_argument("host/username/password/command must not be empty");
    }
    if (port_ == 0) throw std::invalid_argument("port must not be zero");
    if (connect_timeout.count() <= 0) {
        throw std::invalid_argument("connect_timeout must be positive");
    }

    const std::string port_string = std::to_string(port_);
    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    const int gai_result = getaddrinfo(
        host_.c_str(), port_string.c_str(), &hints, &addresses_);
    if (gai_result != 0) {
        throw std::runtime_error(
            std::string("DNS resolution failed: ") + gai_strerror(gai_result));
    }
    current_ = addresses_;
}

TcpPasswordExecOperation::~TcpPasswordExecOperation() {
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    if (addresses_ != nullptr) {
        freeaddrinfo(addresses_);
        addresses_ = nullptr;
        current_ = nullptr;
    }
}

void TcpPasswordExecOperation::advanceToNextAddress() noexcept {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    connect_pending_ = false;
    if (current_ != nullptr) current_ = current_->ai_next;
}

StepResult TcpPasswordExecOperation::step(
    LoopContext&,
    const ReadySet& ready,
    MonoTime now) {
    // Phase 1: nonblocking TCP connect.
    while (!connected_) {
        if (now >= deadline_) {
            SshError error;
            error.domain = ErrorDomain::kTimeout;
            error.code = "tcp_connect_timeout";
            error.message = "TCP connect timed out";
            return StepResult::failed(std::move(error));
        }
        if (current_ == nullptr) {
            SshError error;
            error.domain = ErrorDomain::kSystem;
            error.code = "tcp_connect_failed";
            error.message = "all TCP connect attempts failed";
            return StepResult::failed(std::move(error));
        }
        if (fd_ < 0) {
            fd_ = socket(current_->ai_family,
                         current_->ai_socktype | SOCK_CLOEXEC,
                         current_->ai_protocol);
            if (fd_ < 0) {
                current_ = current_->ai_next;
                continue;
            }
            try {
                setNonBlocking(fd_);
            } catch (...) {
                advanceToNextAddress();
                continue;
            }
        }
        if (!connect_pending_) {
            const int result = connect(fd_, current_->ai_addr, current_->ai_addrlen);
            if (result == 0) {
                connected_ = true;
                break;
            }
            if (errno == EINPROGRESS) {
                connect_pending_ = true;
                return StepResult::waitIo({{fd_, POLLOUT}});
            }
            advanceToNextAddress();
            continue;
        }
        if (!ready.ready(fd_, POLLOUT)) {
            return StepResult::waitIo({{fd_, POLLOUT}});
        }
        int socket_error = 0;
        socklen_t socket_error_len = sizeof(socket_error);
        if (getsockopt(fd_, SOL_SOCKET, SO_ERROR, &socket_error,
                       &socket_error_len) != 0) {
            SshError error;
            error.domain = ErrorDomain::kSystem;
            error.code = "tcp_connect_getsockopt_failed";
            error.message = std::strerror(errno);
            return StepResult::failed(std::move(error));
        }
        if (socket_error != 0) {
            errno = socket_error;
            advanceToNextAddress();
            continue;
        }
        connected_ = true;
        break;
    }

    // Phase 2: nonblocking handshake.
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

    // Phase 3: password auth.
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

    // Phase 4: open channel.
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

    // Phase 5: exec.
    if (!exec_started_) {
        exec_started_ = true;
        const int result = libssh2_channel_exec(channel_, command_.c_str());
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

    // Phase 6: read stdout until EOF.
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
        close_started_ = true;
    }

    // Phase 7: close channel and return exit status.
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


TcpPrivateKeyExecOperation::TcpPrivateKeyExecOperation(
    std::string host,
    uint16_t port,
    std::string username,
    std::string private_key,
    std::string passphrase,
    std::string command,
    std::chrono::milliseconds connect_timeout)
    : host_(std::move(host)),
      port_(port),
      username_(std::move(username)),
      private_key_(std::move(private_key)),
      passphrase_(std::move(passphrase)),
      command_(std::move(command)),
      deadline_(MonoClock::now() + connect_timeout) {
    if (host_.empty() || username_.empty() || private_key_.empty() || command_.empty()) {
        throw std::invalid_argument("host/username/private key/command must not be empty");
    }
    if (port_ == 0) throw std::invalid_argument("port must not be zero");
    if (connect_timeout.count() <= 0) {
        throw std::invalid_argument("connect_timeout must be positive");
    }

    const std::string port_string = std::to_string(port_);
    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    const int gai_result = getaddrinfo(
        host_.c_str(), port_string.c_str(), &hints, &addresses_);
    if (gai_result != 0) {
        throw std::runtime_error(
            std::string("DNS resolution failed: ") + gai_strerror(gai_result));
    }
    current_ = addresses_;
}

TcpPrivateKeyExecOperation::~TcpPrivateKeyExecOperation() {
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    if (addresses_ != nullptr) {
        freeaddrinfo(addresses_);
        addresses_ = nullptr;
        current_ = nullptr;
    }
}

void TcpPrivateKeyExecOperation::advanceToNextAddress() noexcept {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    connect_pending_ = false;
    if (current_ != nullptr) current_ = current_->ai_next;
}

StepResult TcpPrivateKeyExecOperation::step(
    LoopContext&,
    const ReadySet& ready,
    MonoTime now) {
    while (!connected_) {
        if (now >= deadline_) {
            SshError error;
            error.domain = ErrorDomain::kTimeout;
            error.code = "tcp_connect_timeout";
            error.message = "TCP connect timed out";
            return StepResult::failed(std::move(error));
        }
        if (current_ == nullptr) {
            SshError error;
            error.domain = ErrorDomain::kSystem;
            error.code = "tcp_connect_failed";
            error.message = "all TCP connect attempts failed";
            return StepResult::failed(std::move(error));
        }
        if (fd_ < 0) {
            fd_ = socket(current_->ai_family,
                         current_->ai_socktype | SOCK_CLOEXEC,
                         current_->ai_protocol);
            if (fd_ < 0) {
                current_ = current_->ai_next;
                continue;
            }
            try {
                setNonBlocking(fd_);
            } catch (...) {
                advanceToNextAddress();
                continue;
            }
        }
        if (!connect_pending_) {
            const int result = connect(fd_, current_->ai_addr, current_->ai_addrlen);
            if (result == 0) {
                connected_ = true;
                break;
            }
            if (errno == EINPROGRESS) {
                connect_pending_ = true;
                return StepResult::waitIo({{fd_, POLLOUT}});
            }
            advanceToNextAddress();
            continue;
        }
        if (!ready.ready(fd_, POLLOUT)) {
            return StepResult::waitIo({{fd_, POLLOUT}});
        }
        int socket_error = 0;
        socklen_t socket_error_len = sizeof(socket_error);
        if (getsockopt(fd_, SOL_SOCKET, SO_ERROR, &socket_error,
                       &socket_error_len) != 0) {
            SshError error;
            error.domain = ErrorDomain::kSystem;
            error.code = "tcp_connect_getsockopt_failed";
            error.message = std::strerror(errno);
            return StepResult::failed(std::move(error));
        }
        if (socket_error != 0) {
            errno = socket_error;
            advanceToNextAddress();
            continue;
        }
        connected_ = true;
        break;
    }

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
        const int result = libssh2_channel_exec(channel_, command_.c_str());
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
        close_started_ = true;
    }

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
} // namespace sshnative

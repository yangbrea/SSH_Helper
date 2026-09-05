#include "ssh_handshake_operation.h"

#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <system_error>
#include <utility>
#include <vector>

#include "ssh_error.h"
#include "ssh_hostkey.h"
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

TcpHandshakeOperation::TcpHandshakeOperation(
    std::string host,
    uint16_t port,
    std::chrono::milliseconds connect_timeout)
    : host_(std::move(host)),
      port_(port),
      deadline_(MonoClock::now() + connect_timeout) {
    if (host_.empty()) throw std::invalid_argument("host must not be empty");
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

TcpHandshakeOperation::~TcpHandshakeOperation() {
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

void TcpHandshakeOperation::advanceToNextAddress() noexcept {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    connect_pending_ = false;
    if (current_ != nullptr) current_ = current_->ai_next;
}

StepResult TcpHandshakeOperation::step(
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

    int key_type = 0;
    const std::vector<uint8_t> blob = session_.hostKey(&key_type);
    if (blob.empty()) {
        SshError error;
        error.domain = ErrorDomain::kHostKey;
        error.code = "host_key_unavailable";
        error.message = "handshake succeeded but host key was not available";
        return StepResult::failed(std::move(error));
    }
    return StepResult::complete(
        "fingerprint=" + hostKeySha256Fingerprint(blob) +
        "\nkeyType=" + hostKeyTypeName(key_type));
}

} // namespace sshnative

#include "ssh_connect_operation.h"

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
#include "ssh_socket.h"

namespace sshnative {

namespace {

void setNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        throw std::system_error(errno, std::generic_category(), "fcntl");
    }
}

SshError connectError(const char* operation_code, const std::string& message) {
    SshError error;
    error.domain = ErrorDomain::kSystem;
    error.code = operation_code;
    error.message = message;
    error.system_errno = errno;
    return error;
}

} // namespace

TcpConnectOperation::TcpConnectOperation(
    std::string host,
    uint16_t port,
    std::chrono::milliseconds timeout)
    : host_(std::move(host)),
      port_(port),
      deadline_(MonoClock::now() + timeout) {
    if (host_.empty()) throw std::invalid_argument("host must not be empty");
    if (port_ == 0) throw std::invalid_argument("port must not be zero");
    if (timeout.count() <= 0) throw std::invalid_argument("timeout must be positive");

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

TcpConnectOperation::~TcpConnectOperation() {
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

void TcpConnectOperation::advanceToNextAddress() noexcept {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    connect_pending_ = false;
    if (current_ != nullptr) current_ = current_->ai_next;
}

StepResult TcpConnectOperation::step(
    LoopContext&,
    const ReadySet& ready,
    MonoTime now) {
    if (done_) return StepResult::complete("connected");
    if (now >= deadline_) {
        SshError error;
        error.domain = ErrorDomain::kTimeout;
        error.code = "tcp_connect_timeout";
        error.message = "TCP connect timed out";
        return StepResult::failed(std::move(error));
    }

    while (current_ != nullptr) {
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
                done_ = true;
                return StepResult::complete("connected");
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
            return StepResult::failed(connectError(
                "tcp_connect_getsockopt_failed", std::strerror(errno)));
        }
        if (socket_error != 0) {
            errno = socket_error;
            advanceToNextAddress();
            continue;
        }
        done_ = true;
        return StepResult::complete("connected");
    }

    return StepResult::failed(connectError(
        "tcp_connect_failed", "all TCP connect attempts failed"));
}

} // namespace sshnative

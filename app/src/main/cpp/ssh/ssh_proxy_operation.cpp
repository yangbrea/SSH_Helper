#include "ssh_proxy_operation.h"

#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <system_error>
#include <utility>

#include "ssh_error.h"
#include "ssh_socket.h"

namespace sshnative {

namespace {

constexpr size_t kMaxResponseBytes = 16 * 1024;

void setNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        throw std::system_error(errno, std::generic_category(), "fcntl");
    }
}

std::string base64Encode(const std::string& input) {
    static constexpr char kTable[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string output;
    output.reserve(((input.size() + 2) / 3) * 4);
    size_t i = 0;
    while (i + 2 < input.size()) {
        const uint32_t value = (static_cast<uint8_t>(input[i]) << 16) |
            (static_cast<uint8_t>(input[i + 1]) << 8) |
            static_cast<uint8_t>(input[i + 2]);
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
        output.push_back(kTable[(value >> 6) & 0x3F]);
        output.push_back(kTable[value & 0x3F]);
        i += 3;
    }
    const size_t remaining = input.size() - i;
    if (remaining == 1) {
        const uint32_t value = static_cast<uint8_t>(input[i]) << 16;
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
        output.push_back('=');
        output.push_back('=');
    } else if (remaining == 2) {
        const uint32_t value = (static_cast<uint8_t>(input[i]) << 16) |
            (static_cast<uint8_t>(input[i + 1]) << 8);
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
        output.push_back(kTable[(value >> 6) & 0x3F]);
        output.push_back('=');
    }
    return output;
}

} // namespace

HttpProxyConnectOperation::HttpProxyConnectOperation(
    std::string proxy_host,
    uint16_t proxy_port,
    std::string target_host,
    uint16_t target_port,
    std::string username,
    std::string password,
    std::chrono::milliseconds timeout)
    : proxy_host_(std::move(proxy_host)),
      proxy_port_(proxy_port),
      target_host_(std::move(target_host)),
      target_port_(target_port),
      username_(std::move(username)),
      password_(std::move(password)),
      deadline_(MonoClock::now() + timeout) {
    if (proxy_host_.empty() || target_host_.empty()) {
        throw std::invalid_argument("proxy/target host must not be empty");
    }
    if (proxy_port_ == 0 || target_port_ == 0) {
        throw std::invalid_argument("proxy/target port must not be zero");
    }
    if (timeout.count() <= 0) throw std::invalid_argument("timeout must be positive");

    const std::string proxy_port_string = std::to_string(proxy_port_);
    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    const int gai_result = getaddrinfo(
        proxy_host_.c_str(), proxy_port_string.c_str(), &hints, &addresses_);
    if (gai_result != 0) {
        throw std::runtime_error(
            std::string("DNS resolution failed: ") + gai_strerror(gai_result));
    }
    current_ = addresses_;

    const std::string authority =
        target_host_ + ":" + std::to_string(target_port_);
    request_ = "CONNECT " + authority + " HTTP/1.1\r\n";
    request_ += "Host: " + authority + "\r\n";
    request_ += "Proxy-Connection: keep-alive\r\n";
    if (!username_.empty() || !password_.empty()) {
        request_ += "Proxy-Authorization: Basic " +
            base64Encode(username_ + ":" + password_) + "\r\n";
    }
    request_ += "\r\n";
}

HttpProxyConnectOperation::~HttpProxyConnectOperation() {
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

void HttpProxyConnectOperation::closeFdAndAdvance() noexcept {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    connect_pending_ = false;
    if (current_ != nullptr) current_ = current_->ai_next;
}

StepResult HttpProxyConnectOperation::step(
    LoopContext&,
    const ReadySet& ready,
    MonoTime now) {
    // TCP connect to proxy.
    while (!connected_) {
        if (now >= deadline_) {
            SshError error;
            error.domain = ErrorDomain::kTimeout;
            error.code = "proxy_tcp_connect_timeout";
            error.message = "proxy TCP connect timed out";
            return StepResult::failed(std::move(error));
        }
        if (current_ == nullptr) {
            SshError error;
            error.domain = ErrorDomain::kSystem;
            error.code = "proxy_tcp_connect_failed";
            error.message = "all proxy TCP connect attempts failed";
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
                closeFdAndAdvance();
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
            closeFdAndAdvance();
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
            error.code = "proxy_tcp_getsockopt_failed";
            error.message = std::strerror(errno);
            return StepResult::failed(std::move(error));
        }
        if (socket_error != 0) {
            errno = socket_error;
            closeFdAndAdvance();
            continue;
        }
        connected_ = true;
        break;
    }

    // Send CONNECT request.
    if (!request_sent_) {
        if (now >= deadline_) {
            SshError error;
            error.domain = ErrorDomain::kTimeout;
            error.code = "http_proxy_write_timeout";
            error.message = "HTTP proxy write timed out";
            return StepResult::failed(std::move(error));
        }
        const size_t remaining = request_.size() - request_offset_;
        const ssize_t sent = send(fd_, request_.data() + request_offset_,
                                  remaining, MSG_NOSIGNAL);
        if (sent > 0) {
            request_offset_ += static_cast<size_t>(sent);
            if (request_offset_ == request_.size()) request_sent_ = true;
            return StepResult::progress(static_cast<size_t>(sent));
        }
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            return StepResult::waitIo({{fd_, POLLOUT}});
        }
        SshError error;
        error.domain = ErrorDomain::kProxy;
        error.code = "http_proxy_write_failed";
        error.message = "HTTP proxy write failed";
        return StepResult::failed(std::move(error));
    }

    // Read response headers.
    if (response_.find("\r\n\r\n") == std::string::npos) {
        if (now >= deadline_) {
            SshError error;
            error.domain = ErrorDomain::kTimeout;
            error.code = "http_proxy_response_timeout";
            error.message = "HTTP proxy response timed out";
            return StepResult::failed(std::move(error));
        }
        if (response_.size() >= kMaxResponseBytes) {
            SshError error;
            error.domain = ErrorDomain::kProxy;
            error.code = "http_proxy_response_too_large";
            error.message = "HTTP proxy response header too large";
            return StepResult::failed(std::move(error));
        }
        char buffer[1024];
        const ssize_t count = recv(fd_, buffer, sizeof(buffer), 0);
        if (count > 0) {
            response_.append(buffer, static_cast<size_t>(count));
            return StepResult::progress(static_cast<size_t>(count));
        }
        if (count == 0) {
            SshError error;
            error.domain = ErrorDomain::kProxy;
            error.code = "http_proxy_closed";
            error.message = "HTTP proxy closed connection during handshake";
            return StepResult::failed(std::move(error));
        }
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return StepResult::waitIo({{fd_, POLLIN}});
        }
        SshError error;
        error.domain = ErrorDomain::kProxy;
        error.code = "http_proxy_read_failed";
        error.message = "HTTP proxy read failed";
        return StepResult::failed(std::move(error));
    }

    const size_t space1 = response_.find(' ');
    const size_t space2 = space1 == std::string::npos
        ? std::string::npos
        : response_.find(' ', space1 + 1);
    if (space1 == std::string::npos || space2 == std::string::npos) {
        SshError error;
        error.domain = ErrorDomain::kProxy;
        error.code = "http_proxy_malformed_status";
        error.message = "HTTP proxy sent malformed status line";
        return StepResult::failed(std::move(error));
    }
    const std::string code_string =
        response_.substr(space1 + 1, space2 - space1 - 1);
    char* end = nullptr;
    const long code = std::strtol(code_string.c_str(), &end, 10);
    if (end == code_string.c_str() || *end != '\0') {
        SshError error;
        error.domain = ErrorDomain::kProxy;
        error.code = "http_proxy_non_numeric_status";
        error.message = "HTTP proxy sent non-numeric status code";
        return StepResult::failed(std::move(error));
    }
    if (code != 200) {
        SshError error;
        error.domain = ErrorDomain::kProxy;
        error.code = "http_proxy_connect_failed";
        error.message = "HTTP CONNECT failed with status " + code_string;
        return StepResult::failed(std::move(error));
    }
    return StepResult::complete("connected");
}

} // namespace sshnative

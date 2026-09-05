#include "ssh_socks5_operation.h"

#include <algorithm>
#include <arpa/inet.h>
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
#include "ssh_socket.h"

namespace sshnative {

namespace {

constexpr uint8_t kSocks5Version = 0x05;
constexpr uint8_t kMethodNoAuth = 0x00;
constexpr uint8_t kMethodUserPass = 0x02;
constexpr uint8_t kCmdConnect = 0x01;
constexpr uint8_t kAtypIpv4 = 0x01;
constexpr uint8_t kAtypDomain = 0x03;
constexpr uint8_t kAtypIpv6 = 0x04;

void setNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        throw std::system_error(errno, std::generic_category(), "fcntl");
    }
}

std::string makeConnectRequest(const std::string& host, uint16_t port) {
    std::string request;
    request.push_back(static_cast<char>(kSocks5Version));
    request.push_back(static_cast<char>(kCmdConnect));
    request.push_back(0);

    struct in_addr ipv4 {};
    struct in6_addr ipv6 {};
    if (inet_pton(AF_INET, host.c_str(), &ipv4) == 1) {
        request.push_back(static_cast<char>(kAtypIpv4));
        request.append(reinterpret_cast<const char*>(&ipv4), sizeof(ipv4));
    } else if (inet_pton(AF_INET6, host.c_str(), &ipv6) == 1) {
        request.push_back(static_cast<char>(kAtypIpv6));
        request.append(reinterpret_cast<const char*>(&ipv6), sizeof(ipv6));
    } else {
        if (host.empty() || host.size() > 255) {
            throw std::invalid_argument("SOCKS5 target host too long or empty");
        }
        request.push_back(static_cast<char>(kAtypDomain));
        request.push_back(static_cast<char>(host.size()));
        request.append(host);
    }
    request.push_back(static_cast<char>((port >> 8) & 0xFF));
    request.push_back(static_cast<char>(port & 0xFF));
    return request;
}

} // namespace

Socks5ProxyConnectOperation::Socks5ProxyConnectOperation(
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
}

Socks5ProxyConnectOperation::~Socks5ProxyConnectOperation() {
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

void Socks5ProxyConnectOperation::closeFdAndAdvance() noexcept {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    connect_pending_ = false;
    if (current_ != nullptr) current_ = current_->ai_next;
}

bool Socks5ProxyConnectOperation::writePending(
    const ReadySet&, MonoTime now) {
    if (now >= deadline_) return false;
    while (write_offset_ < write_data_.size()) {
        const size_t remaining = write_data_.size() - write_offset_;
        const ssize_t sent = send(fd_, write_data_.data() + write_offset_,
                                  remaining, MSG_NOSIGNAL);
        if (sent > 0) {
            write_offset_ += static_cast<size_t>(sent);
            continue;
        }
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            return false;
        }
        return false;
    }
    return true;
}

bool Socks5ProxyConnectOperation::readExactly(
    size_t needed, const ReadySet&, MonoTime now) {
    if (now >= deadline_) return false;
    while (read_data_.size() < needed) {
        char buffer[256];
        const size_t want = std::min(sizeof(buffer), needed - read_data_.size());
        const ssize_t count = recv(fd_, buffer, want, 0);
        if (count > 0) {
            read_data_.append(buffer, static_cast<size_t>(count));
            continue;
        }
        if (count == 0) return false;
        if (errno == EAGAIN || errno == EWOULDBLOCK) return false;
        return false;
    }
    return true;
}

StepResult Socks5ProxyConnectOperation::step(
    LoopContext& context,
    const ReadySet& ready,
    MonoTime now) {
    // Phase: TCP connect to proxy.
    if (phase_ == Phase::kConnect) {
        while (!connected_) {
            if (now >= deadline_) {
                SshError error;
                error.domain = ErrorDomain::kTimeout;
                error.code = "socks5_tcp_connect_timeout";
                error.message = "SOCKS5 proxy TCP connect timed out";
                return StepResult::failed(std::move(error));
            }
            if (current_ == nullptr) {
                SshError error;
                error.domain = ErrorDomain::kSystem;
                error.code = "socks5_tcp_connect_failed";
                error.message = "all SOCKS5 proxy TCP connect attempts failed";
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
                error.code = "socks5_tcp_getsockopt_failed";
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
        if (!connected_) return StepResult::waitIo({{fd_, POLLOUT}});
        phase_ = Phase::kWriteGreeting;
        const bool use_auth = !username_.empty() || !password_.empty();
        write_data_.clear();
        write_data_.push_back(static_cast<char>(kSocks5Version));
        write_data_.push_back(static_cast<char>(use_auth ? 2 : 1));
        write_data_.push_back(static_cast<char>(kMethodNoAuth));
        if (use_auth) write_data_.push_back(static_cast<char>(kMethodUserPass));
        write_offset_ = 0;
    }

    // Write current payload.
    if (phase_ == Phase::kWriteGreeting || phase_ == Phase::kWriteAuth ||
        phase_ == Phase::kWriteConnect) {
        if (!writePending(ready, now)) {
            if (now >= deadline_) {
                SshError error;
                error.domain = ErrorDomain::kTimeout;
                error.code = "socks5_write_timeout";
                error.message = "SOCKS5 proxy write timed out";
                return StepResult::failed(std::move(error));
            }
            return StepResult::waitIo({{fd_, POLLOUT}});
        }
        if (phase_ == Phase::kWriteGreeting) phase_ = Phase::kReadMethod;
        else if (phase_ == Phase::kWriteAuth) phase_ = Phase::kReadAuthReply;
        else phase_ = Phase::kReadReplyHead;
        read_data_.clear();
        read_need_ = 0;
    }

    // Read fixed-size replies.
    if (phase_ == Phase::kReadMethod) {
        read_need_ = 2;
        if (!readExactly(read_need_, ready, now)) {
            if (now >= deadline_) {
                SshError error;
                error.domain = ErrorDomain::kTimeout;
                error.code = "socks5_read_timeout";
                error.message = "SOCKS5 proxy read timed out";
                return StepResult::failed(std::move(error));
            }
            return StepResult::waitIo({{fd_, POLLIN}});
        }
        if (static_cast<uint8_t>(read_data_[0]) != kSocks5Version) {
            SshError error;
            error.domain = ErrorDomain::kProxy;
            error.code = "socks5_invalid_version";
            error.message = "SOCKS5 proxy sent invalid version";
            return StepResult::failed(std::move(error));
        }
        selected_method_ = static_cast<uint8_t>(read_data_[1]);
        const bool use_auth = !username_.empty() || !password_.empty();
        if (selected_method_ == kMethodNoAuth && use_auth) {
            SshError error;
            error.domain = ErrorDomain::kProxy;
            error.code = "socks5_unauthenticated_required";
            error.message = "SOCKS5 proxy requested unauthenticated connection";
            return StepResult::failed(std::move(error));
        }
        if (selected_method_ == kMethodUserPass) {
            if (username_.size() > 255 || password_.size() > 255) {
                SshError error;
                error.domain = ErrorDomain::kProxy;
                error.code = "socks5_credentials_too_long";
                error.message = "SOCKS5 credentials too long";
                return StepResult::failed(std::move(error));
            }
            write_data_.clear();
            write_data_.push_back(0x01);
            write_data_.push_back(static_cast<char>(username_.size()));
            write_data_.append(username_);
            write_data_.push_back(static_cast<char>(password_.size()));
            write_data_.append(password_);
            write_offset_ = 0;
            phase_ = Phase::kWriteAuth;
            return StepResult::progress(0);
        }
        if (selected_method_ != kMethodNoAuth) {
            SshError error;
            error.domain = ErrorDomain::kProxy;
            error.code = "socks5_no_method";
            error.message = "SOCKS5 proxy rejected all offered methods";
            return StepResult::failed(std::move(error));
        }
        write_data_ = makeConnectRequest(target_host_, target_port_);
        write_offset_ = 0;
        phase_ = Phase::kWriteConnect;
        return StepResult::progress(0);
    }

    if (phase_ == Phase::kReadAuthReply) {
        read_need_ = 2;
        if (!readExactly(read_need_, ready, now)) {
            if (now >= deadline_) {
                SshError error;
                error.domain = ErrorDomain::kTimeout;
                error.code = "socks5_auth_timeout";
                error.message = "SOCKS5 proxy authentication timed out";
                return StepResult::failed(std::move(error));
            }
            return StepResult::waitIo({{fd_, POLLIN}});
        }
        if (static_cast<uint8_t>(read_data_[0]) != 0x01 ||
            static_cast<uint8_t>(read_data_[1]) != 0x00) {
            SshError error;
            error.domain = ErrorDomain::kProxy;
            error.code = "socks5_auth_failed";
            error.message = "SOCKS5 proxy authentication failed";
            return StepResult::failed(std::move(error));
        }
        write_data_ = makeConnectRequest(target_host_, target_port_);
        write_offset_ = 0;
        phase_ = Phase::kWriteConnect;
        return StepResult::progress(0);
    }

    if (phase_ == Phase::kReadReplyHead) {
        read_need_ = 4;
        if (!readExactly(read_need_, ready, now)) {
            if (now >= deadline_) {
                SshError error;
                error.domain = ErrorDomain::kTimeout;
                error.code = "socks5_connect_timeout";
                error.message = "SOCKS5 CONNECT timed out";
                return StepResult::failed(std::move(error));
            }
            return StepResult::waitIo({{fd_, POLLIN}});
        }
        if (static_cast<uint8_t>(read_data_[0]) != kSocks5Version) {
            SshError error;
            error.domain = ErrorDomain::kProxy;
            error.code = "socks5_connect_invalid_version";
            error.message = "SOCKS5 proxy sent invalid CONNECT version";
            return StepResult::failed(std::move(error));
        }
        if (static_cast<uint8_t>(read_data_[1]) != 0x00) {
            SshError error;
            error.domain = ErrorDomain::kProxy;
            error.code = "socks5_connect_failed";
            error.message = "SOCKS5 CONNECT failed";
            return StepResult::failed(std::move(error));
        }
        reply_atyp_ = static_cast<uint8_t>(read_data_[3]);
        size_t address_bytes = 0;
        switch (reply_atyp_) {
            case kAtypIpv4: address_bytes = 4; break;
            case kAtypIpv6: address_bytes = 16; break;
            case kAtypDomain: address_bytes = 1; break; // length byte read next
            default:
                SshError error;
                error.domain = ErrorDomain::kProxy;
                error.code = "socks5_unknown_atyp";
                error.message = "SOCKS5 proxy sent unknown address type";
                return StepResult::failed(std::move(error));
        }
        read_data_.clear();
        read_need_ = address_bytes + 2;
        phase_ = Phase::kReadAddress;
        return StepResult::progress(0);
    }

    if (phase_ == Phase::kReadAddress) {
        if (reply_atyp_ == kAtypDomain && read_data_.empty()) {
            read_need_ = 1;
            if (!readExactly(read_need_, ready, now)) {
                if (now >= deadline_) {
                    SshError error;
                    error.domain = ErrorDomain::kTimeout;
                    error.code = "socks5_address_timeout";
                    error.message = "SOCKS5 proxy address read timed out";
                    return StepResult::failed(std::move(error));
                }
                return StepResult::waitIo({{fd_, POLLIN}});
            }
            const size_t domain_len = static_cast<uint8_t>(read_data_[0]);
            read_data_.clear();
            read_need_ = domain_len + 2;
        }
        if (!readExactly(read_need_, ready, now)) {
            if (now >= deadline_) {
                SshError error;
                error.domain = ErrorDomain::kTimeout;
                error.code = "socks5_address_timeout";
                error.message = "SOCKS5 proxy address read timed out";
                return StepResult::failed(std::move(error));
            }
            return StepResult::waitIo({{fd_, POLLIN}});
        }
        phase_ = Phase::kDone;
        context.storeTransportFd(fd_);
        fd_ = -1;
        return StepResult::complete("connected");
    }

    if (phase_ == Phase::kDone) return StepResult::complete("connected");
    return StepResult::failed(SshError{ErrorDomain::kInternal, "unreachable",
                                       "unexpected SOCKS5 operation state"});
}

} // namespace sshnative

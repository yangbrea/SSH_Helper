#include "ssh_forward_operation.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <utility>
#include <vector>

#include "ssh_error.h"
#include "ssh_libssh2_nonblocking.h"
#include "ssh_persistent_session.h"
#include "ssh_socket.h"

namespace sshnative {

namespace {

constexpr int kListenBacklog = 64;
constexpr size_t kReadChunkSize = 16 * 1024;
constexpr size_t kHighWater = 256 * 1024;
constexpr std::chrono::seconds kConnectTimeout{15};

SshError noSessionError() {
    SshError error;
    error.domain = ErrorDomain::kInternal;
    error.code = "no_active_session";
    error.message = "no active authenticated SSH session";
    return error;
}

bool activeSessionInfo(LoopContext& context, LIBSSH2_SESSION** session, int* fd) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return false;
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    *session = session_resource->session()->get();
    *fd = session_resource->fd();
    return *session != nullptr && *fd >= 0;
}

void setNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        throw std::runtime_error("failed to set socket non-blocking");
    }
}

int createLocalListener(const std::string& bind_address, uint16_t port,
                        int* actual_port) {
    const std::string port_string = std::to_string(port);
    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    hints.ai_flags = AI_PASSIVE;

    struct addrinfo* addresses = nullptr;
    const int gai_result = getaddrinfo(
        bind_address.empty() ? nullptr : bind_address.c_str(),
        port_string.c_str(), &hints, &addresses);
    if (gai_result != 0) {
        throw std::runtime_error(std::string("local listener address lookup failed: ") +
                                 gai_strerror(gai_result));
    }

    int last_error = EADDRNOTAVAIL;
    int listener = -1;
    for (struct addrinfo* address = addresses; address != nullptr;
         address = address->ai_next) {
        const int fd = socket(
            address->ai_family,
            address->ai_socktype | SOCK_CLOEXEC,
            address->ai_protocol);
        if (fd < 0) {
            last_error = errno;
            continue;
        }
        int reuse = 1;
        (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
        try {
            setNonBlocking(fd);
            if (bind(fd, address->ai_addr, address->ai_addrlen) != 0 ||
                listen(fd, kListenBacklog) != 0) {
                last_error = errno;
                closeFd(fd);
                continue;
            }
        } catch (...) {
            closeFd(fd);
            continue;
        }

        struct sockaddr_storage bound {};
        socklen_t bound_len = sizeof(bound);
        if (getsockname(fd, reinterpret_cast<struct sockaddr*>(&bound),
                        &bound_len) == 0) {
            if (bound.ss_family == AF_INET) {
                *actual_port = ntohs(
                    reinterpret_cast<const struct sockaddr_in*>(&bound)->sin_port);
            } else if (bound.ss_family == AF_INET6) {
                *actual_port = ntohs(
                    reinterpret_cast<const struct sockaddr_in6*>(&bound)->sin6_port);
            }
        }
        listener = fd;
        break;
    }

    freeaddrinfo(addresses);
    if (listener < 0) {
        throw std::runtime_error(std::string("failed to create local listener: ") +
                                 std::strerror(last_error));
    }
    return listener;
}

void appendPollInterest(IoInterest& interest, int fd, short events) {
    if (fd < 0 || events == 0) return;
    for (const auto& item : interest) {
        if (item.fd == fd) {
            // The caller combines only matching readiness classes; a duplicate is
            // not expected in normal use, but keeping it simple is safe.
            return;
        }
    }
    interest.push_back({fd, events});
}

} // namespace

StartLocalForwardOperation::StartLocalForwardOperation(
    std::string bind_address,
    uint16_t listen_port,
    std::string target_host,
    uint16_t target_port)
    : bind_address_(std::move(bind_address)),
      listen_port_(listen_port),
      target_host_(std::move(target_host)),
      target_port_(target_port) {
    if (target_host_.empty() || target_port_ == 0) {
        throw std::invalid_argument("local forward target must not be empty");
    }
    listen_fd_ = createLocalListener(bind_address_, listen_port_, &actual_port_);
}

StartLocalForwardOperation::~StartLocalForwardOperation() {
    closeListenFd();
}

void StartLocalForwardOperation::closeListenFd() noexcept {
    if (listen_fd_ >= 0) {
        closeFd(listen_fd_);
        listen_fd_ = -1;
    }
}

StepResult StartLocalForwardOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    if (started_) {
        return StepResult::complete(
            "id=" + std::to_string(group_id_) +
            "\nport=" + std::to_string(actual_port_));
    }

    if (listen_fd_ < 0) {
        return StepResult::failed(SshError{
            ErrorDomain::kInternal, "local_listener_closed",
            "local forwarding listener is not available"});
    }

    LIBSSH2_SESSION* session = nullptr;
    int ssh_fd = -1;
    if (!activeSessionInfo(context, &session, &ssh_fd)) {
        closeListenFd();
        return StepResult::failed(noSessionError());
    }

    auto accept_operation = std::make_unique<LocalForwardAcceptOperation>(
        listen_fd_, session, ssh_fd, target_host_, target_port_);
    listen_fd_ = -1;
    group_id_ = context.spawnBackground(std::move(accept_operation));
    started_ = true;
    return StepResult::complete(
        "id=" + std::to_string(group_id_) +
        "\nport=" + std::to_string(actual_port_));
}

StartRemoteForwardOperation::StartRemoteForwardOperation(
    std::string bind_address,
    uint16_t listen_port,
    std::string target_host,
    uint16_t target_port)
    : bind_address_(std::move(bind_address)),
      listen_port_(listen_port),
      target_host_(std::move(target_host)),
      target_port_(target_port) {
    if (target_host_.empty() || target_port_ == 0) {
        throw std::invalid_argument("remote forward target must not be empty");
    }
}

StartRemoteForwardOperation::~StartRemoteForwardOperation() {
    if (listener_ != nullptr) {
        libssh2_channel_forward_cancel(listener_);
        listener_ = nullptr;
    }
}

StepResult StartRemoteForwardOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    if (started_) {
        return StepResult::complete(
            "id=" + std::to_string(group_id_) +
            "\nport=" + std::to_string(bound_port_));
    }

    LIBSSH2_SESSION* session = nullptr;
    int ssh_fd = -1;
    if (!activeSessionInfo(context, &session, &ssh_fd)) {
        return StepResult::failed(noSessionError());
    }

    if (listener_ == nullptr) {
        int bound_port = 0;
        Libssh2CallResult translated = classifyLibssh2Pointer(
            session, ssh_fd,
            libssh2_channel_forward_listen_ex(
                session,
                bind_address_.empty() ? nullptr : bind_address_.c_str(),
                listen_port_, &bound_port, 16),
            ErrorDomain::kChannel, "channel_forward_listen");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                listener_ = static_cast<LIBSSH2_LISTENER*>(translated.pointer);
                bound_port_ = bound_port;
                break;
        }
    }

    if (listener_ != nullptr) {
        auto accept_operation = std::make_unique<RemoteForwardAcceptOperation>(
            listener_, session, ssh_fd, target_host_, target_port_);
        listener_ = nullptr;
        group_id_ = context.spawnBackground(std::move(accept_operation));
        started_ = true;
        return StepResult::complete(
            "id=" + std::to_string(group_id_) +
            "\nport=" + std::to_string(bound_port_));
    }

    return StepResult::noProgress();
}

StepResult CloseForwardOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    context.cancelBackgroundGroup(group_id_);
    return StepResult::complete("closed");
}

LocalForwardAcceptOperation::LocalForwardAcceptOperation(
    int listen_fd,
    LIBSSH2_SESSION* session,
    int ssh_fd,
    std::string target_host,
    uint16_t target_port)
    : listen_fd_(listen_fd),
      session_(session),
      ssh_fd_(ssh_fd),
      target_host_(std::move(target_host)),
      target_port_(target_port) {
    if (listen_fd_ < 0 || session_ == nullptr || ssh_fd_ < 0 ||
        target_host_.empty() || target_port_ == 0) {
        throw std::invalid_argument("invalid local forward accept operation");
    }
}

LocalForwardAcceptOperation::~LocalForwardAcceptOperation() {
    if (listen_fd_ >= 0) {
        closeFd(listen_fd_);
        listen_fd_ = -1;
    }
}

StepResult LocalForwardAcceptOperation::step(
    LoopContext& context,
    const ReadySet& ready,
    MonoTime) {
    if (listen_fd_ < 0) return StepResult::complete();
    if (!ready.ready(listen_fd_, POLLIN)) {
        return StepResult::waitIo({{listen_fd_, POLLIN}});
    }

    struct sockaddr_storage peer {};
    socklen_t peer_len = sizeof(peer);
    const int client = accept4(
        listen_fd_, reinterpret_cast<struct sockaddr*>(&peer), &peer_len,
        SOCK_NONBLOCK | SOCK_CLOEXEC);
    if (client < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return StepResult::waitIo({{listen_fd_, POLLIN}});
        }
        // Transient resource exhaustion: pause briefly rather than spinning.
        return StepResult::noProgress();
    }

    auto connection = std::make_unique<ForwardConnectionOperation>(
        ForwardKind::kLocal, session_, ssh_fd_, client, nullptr,
        target_host_, target_port_);
    context.spawnBackground(std::move(connection), backgroundGroup());
    return StepResult::progress(1);
}

void LocalForwardAcceptOperation::onCancel(LoopContext&) noexcept {
    if (listen_fd_ >= 0) {
        closeFd(listen_fd_);
        listen_fd_ = -1;
    }
}

RemoteForwardAcceptOperation::RemoteForwardAcceptOperation(
    LIBSSH2_LISTENER* listener,
    LIBSSH2_SESSION* session,
    int ssh_fd,
    std::string target_host,
    uint16_t target_port)
    : listener_(listener),
      session_(session),
      ssh_fd_(ssh_fd),
      target_host_(std::move(target_host)),
      target_port_(target_port) {
    if (listener_ == nullptr || session_ == nullptr || ssh_fd_ < 0 ||
        target_host_.empty() || target_port_ == 0) {
        throw std::invalid_argument("invalid remote forward accept operation");
    }
}

RemoteForwardAcceptOperation::~RemoteForwardAcceptOperation() {
    if (listener_ != nullptr) {
        libssh2_channel_forward_cancel(listener_);
        listener_ = nullptr;
    }
}

StepResult RemoteForwardAcceptOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    if (listener_ == nullptr) return StepResult::complete();

    Libssh2CallResult translated = classifyLibssh2Pointer(
        session_, ssh_fd_,
        libssh2_channel_forward_accept(listener_),
        ErrorDomain::kChannel, "channel_forward_accept");
    switch (translated.kind) {
        case Libssh2CallKind::kWouldBlock:
            return StepResult::waitIo(std::move(translated.interest));
        case Libssh2CallKind::kFailed:
            return StepResult::failed(std::move(translated.error));
        case Libssh2CallKind::kSucceeded:
            break;
    }

    auto* channel = static_cast<LIBSSH2_CHANNEL*>(translated.pointer);
    auto connection = std::make_unique<ForwardConnectionOperation>(
        ForwardKind::kRemote, session_, ssh_fd_, -1, channel,
        target_host_, target_port_);
    context.spawnBackground(std::move(connection), backgroundGroup());
    return StepResult::progress(1);
}

void RemoteForwardAcceptOperation::onCancel(LoopContext&) noexcept {
    if (listener_ != nullptr) {
        libssh2_channel_forward_cancel(listener_);
        listener_ = nullptr;
    }
}

ForwardConnectionOperation::ForwardConnectionOperation(
    ForwardKind kind,
    LIBSSH2_SESSION* session,
    int ssh_fd,
    int local_fd,
    LIBSSH2_CHANNEL* channel,
    std::string target_host,
    uint16_t target_port)
    : kind_(kind),
      session_(session),
      ssh_fd_(ssh_fd),
      local_fd_(local_fd),
      channel_(channel),
      target_host_(std::move(target_host)),
      target_port_(target_port),
      phase_(kind == ForwardKind::kLocal ? Phase::kOpening : Phase::kConnecting) {
    if (session_ == nullptr || ssh_fd_ < 0 || target_host_.empty() ||
        target_port_ == 0) {
        throw std::invalid_argument("invalid forward connection operation");
    }

    if (kind_ == ForwardKind::kRemote) {
        const std::string port_string = std::to_string(target_port_);
        struct addrinfo hints {};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        hints.ai_protocol = IPPROTO_TCP;
        const int gai_result = getaddrinfo(
            target_host_.c_str(), port_string.c_str(), &hints,
            &connect_addresses_);
        if (gai_result == 0) {
            connect_current_ = connect_addresses_;
            connect_deadline_ = MonoClock::now() + kConnectTimeout;
        }
    }
}

ForwardConnectionOperation::~ForwardConnectionOperation() {
    cleanup();
}

void ForwardConnectionOperation::closeLocalSocket() noexcept {
    if (connect_fd_ >= 0) {
        closeFd(connect_fd_);
        connect_fd_ = -1;
    }
    if (local_fd_ >= 0) {
        closeFd(local_fd_);
        local_fd_ = -1;
    }
}

void ForwardConnectionOperation::closeChannel() noexcept {
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
}

void ForwardConnectionOperation::closeConnectResources() noexcept {
    if (connect_addresses_ != nullptr) {
        freeaddrinfo(connect_addresses_);
        connect_addresses_ = nullptr;
        connect_current_ = nullptr;
    }
}

void ForwardConnectionOperation::cleanup() noexcept {
    closeConnectResources();
    closeChannel();
    closeLocalSocket();
    phase_ = Phase::kDone;
}

void ForwardConnectionOperation::onCancel(LoopContext&) noexcept {
    cleanup();
}

StepResult ForwardConnectionOperation::openingStep() {
    if (channel_ != nullptr) {
        phase_ = Phase::kPumping;
        return StepResult::progress(1);
    }

    Libssh2CallResult translated = classifyLibssh2Pointer(
        session_, ssh_fd_,
        libssh2_channel_direct_tcpip_ex(
            session_, target_host_.c_str(), target_port_,
            "127.0.0.1", 0),
        ErrorDomain::kChannel, "channel_direct_tcpip");
    switch (translated.kind) {
        case Libssh2CallKind::kWouldBlock:
            return StepResult::waitIo(std::move(translated.interest));
        case Libssh2CallKind::kFailed:
            cleanup();
            return StepResult::complete();
        case Libssh2CallKind::kSucceeded:
            channel_ = static_cast<LIBSSH2_CHANNEL*>(translated.pointer);
            phase_ = Phase::kPumping;
            return StepResult::progress(1);
    }
    return StepResult::noProgress();
}

StepResult ForwardConnectionOperation::connectingStep(
    const ReadySet& ready,
    MonoTime now) {
    if (connect_addresses_ == nullptr || connect_current_ == nullptr) {
        cleanup();
        return StepResult::complete();
    }
    if (now >= connect_deadline_) {
        cleanup();
        return StepResult::complete();
    }

    while (connect_current_ != nullptr) {
        if (connect_fd_ < 0) {
            connect_fd_ = socket(
                connect_current_->ai_family,
                connect_current_->ai_socktype | SOCK_CLOEXEC,
                connect_current_->ai_protocol);
            if (connect_fd_ < 0) {
                connect_current_ = connect_current_->ai_next;
                continue;
            }
            try {
                setNonBlocking(connect_fd_);
            } catch (...) {
                closeFd(connect_fd_);
                connect_fd_ = -1;
                connect_current_ = connect_current_->ai_next;
                continue;
            }
        }

        if (!connect_pending_) {
            const int result = connect(
                connect_fd_, connect_current_->ai_addr,
                connect_current_->ai_addrlen);
            if (result == 0) {
                local_fd_ = connect_fd_;
                connect_fd_ = -1;
                closeConnectResources();
                phase_ = Phase::kPumping;
                return StepResult::progress(1);
            }
            if (errno == EINPROGRESS) {
                connect_pending_ = true;
                return StepResult::waitIo({{connect_fd_, POLLOUT}});
            }
            closeFd(connect_fd_);
            connect_fd_ = -1;
            connect_current_ = connect_current_->ai_next;
            continue;
        }

        if (!ready.ready(connect_fd_, POLLOUT)) {
            return StepResult::waitIo({{connect_fd_, POLLOUT}});
        }
        int socket_error = 0;
        socklen_t socket_error_len = sizeof(socket_error);
        if (getsockopt(connect_fd_, SOL_SOCKET, SO_ERROR, &socket_error,
                       &socket_error_len) != 0 || socket_error != 0) {
            closeFd(connect_fd_);
            connect_fd_ = -1;
            connect_pending_ = false;
            connect_current_ = connect_current_->ai_next;
            continue;
        }
        local_fd_ = connect_fd_;
        connect_fd_ = -1;
        closeConnectResources();
        phase_ = Phase::kPumping;
        return StepResult::progress(1);
    }

    cleanup();
    return StepResult::complete();
}

StepResult ForwardConnectionOperation::pumpingStep() {
    if (channel_ == nullptr || local_fd_ < 0) {
        cleanup();
        return StepResult::complete();
    }

    // Write buffered bytes to the SSH channel.
    if (!to_channel_.empty()) {
        Libssh2CallResult translated = classifyLibssh2Count(
            session_, ssh_fd_,
            libssh2_channel_write(
                channel_, to_channel_.data(), to_channel_.size()),
            ErrorDomain::kChannel, "channel_write");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                cleanup();
                return StepResult::complete();
            case Libssh2CallKind::kSucceeded:
                if (translated.value > 0) {
                    to_channel_.erase(0, static_cast<size_t>(translated.value));
                    return StepResult::progress(static_cast<size_t>(translated.value));
                }
                break;
        }
    }

    // Write buffered bytes to the local TCP socket.
    if (!to_local_.empty()) {
        const ssize_t written = write(
            local_fd_, to_local_.data(), to_local_.size());
        if (written > 0) {
            to_local_.erase(0, static_cast<size_t>(written));
            return StepResult::progress(static_cast<size_t>(written));
        }
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            return StepResult::waitIo({{local_fd_, POLLOUT}});
        }
        cleanup();
        return StepResult::complete();
    }

    char buffer[kReadChunkSize];

    // Local socket -> SSH channel.
    if (!local_read_eof_ && to_channel_.size() < kHighWater) {
        const ssize_t count = read(local_fd_, buffer, sizeof(buffer));
        if (count > 0) {
            to_channel_.append(buffer, static_cast<size_t>(count));
            return StepResult::progress(static_cast<size_t>(count));
        }
        if (count == 0) {
            local_read_eof_ = true;
            return StepResult::progress(1);
        }
        if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
            local_read_eof_ = true;
            return StepResult::progress(1);
        }
    }

    // SSH channel -> local socket.
    IoInterest channel_wait_interest;
    bool channel_waiting = false;
    if (!channel_read_eof_ && to_local_.size() < kHighWater) {
        Libssh2CallResult translated = classifyLibssh2Count(
            session_, ssh_fd_,
            libssh2_channel_read(channel_, buffer, sizeof(buffer)),
            ErrorDomain::kChannel, "channel_read");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                channel_wait_interest = std::move(translated.interest);
                channel_waiting = true;
                break;
            case Libssh2CallKind::kFailed:
                cleanup();
                return StepResult::complete();
            case Libssh2CallKind::kSucceeded:
                if (translated.value > 0) {
                    to_local_.append(buffer, static_cast<size_t>(translated.value));
                    return StepResult::progress(static_cast<size_t>(translated.value));
                }
                if (libssh2_channel_eof(channel_)) {
                    channel_read_eof_ = true;
                    return StepResult::progress(1);
                }
                break;
        }
    }

    // Propagate half-closes.
    if (local_read_eof_ && !channel_eof_sent_) {
        Libssh2CallResult translated = classifyLibssh2Int(
            session_, ssh_fd_, libssh2_channel_send_eof(channel_),
            ErrorDomain::kChannel, "channel_send_eof");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                cleanup();
                return StepResult::complete();
            case Libssh2CallKind::kSucceeded:
                channel_eof_sent_ = true;
                return StepResult::progress(1);
        }
    }
    if (channel_read_eof_ && !local_write_closed_) {
        shutdown(local_fd_, SHUT_WR);
        local_write_closed_ = true;
        return StepResult::progress(1);
    }

    if (done()) {
        cleanup();
        return StepResult::complete();
    }

    IoInterest interest;
    if (!local_read_eof_ && to_channel_.size() < kHighWater) {
        appendPollInterest(interest, local_fd_, POLLIN);
    }
    if (channel_waiting && !channel_read_eof_ && to_local_.size() < kHighWater) {
        if (channel_wait_interest.empty()) {
            channel_wait_interest = libssh2PollInterest(session_, ssh_fd_);
        }
        for (const auto& item : channel_wait_interest) {
            appendPollInterest(interest, item.fd, item.events);
        }
    }
    if (!to_channel_.empty()) {
        const IoInterest write_interest = libssh2PollInterest(session_, ssh_fd_);
        for (const auto& item : write_interest) {
            appendPollInterest(interest, item.fd, item.events);
        }
    }
    if (!to_local_.empty()) {
        appendPollInterest(interest, local_fd_, POLLOUT);
    }
    if (!interest.empty()) {
        return StepResult::waitIo(std::move(interest));
    }
    return StepResult::noProgress();
}

StepResult ForwardConnectionOperation::step(
    LoopContext&,
    const ReadySet& ready,
    MonoTime now) {
    switch (phase_) {
        case Phase::kOpening:
            return openingStep();
        case Phase::kConnecting:
            return connectingStep(ready, now);
        case Phase::kPumping:
            return pumpingStep();
        case Phase::kClosing:
        case Phase::kDone:
            return StepResult::complete();
    }
    return StepResult::noProgress();
}

} // namespace sshnative

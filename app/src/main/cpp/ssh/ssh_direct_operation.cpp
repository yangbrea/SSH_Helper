#include "ssh_direct_operation.h"

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

bool hasAuthMethod(const char* methods, const char* wanted) {
    if (methods == nullptr) return false;
    const std::string list(methods);
    const std::string needle(wanted);
    size_t start = 0;
    while (start <= list.size()) {
        const size_t end = list.find(',', start);
        const std::string token = list.substr(
            start, end == std::string::npos ? std::string::npos : end - start);
        if (token == needle) return true;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return false;
}

void keyboardInteractiveCallback(
    const char* /* name */,
    int /* name_len */,
    const char* /* instruction */,
    int /* instruction_len */,
    int num_prompts,
    const LIBSSH2_USERAUTH_KBDINT_PROMPT* /* prompts */,
    LIBSSH2_USERAUTH_KBDINT_RESPONSE* responses,
    void** abstract) {
    if (num_prompts != 1 || abstract == nullptr || *abstract == nullptr) {
        return;
    }
    const auto* password = static_cast<const std::string*>(*abstract);
    char* copy = static_cast<char*>(std::malloc(password->size() + 1));
    if (copy == nullptr) return;
    std::memcpy(copy, password->data(), password->size());
    copy[password->size()] = '\0';
    responses[0].text = copy;
    responses[0].length = static_cast<unsigned int>(password->size());
}

} // namespace

TcpPasswordExecOperation::TcpPasswordExecOperation(
    std::string host,
    uint16_t port,
    std::string username,
    std::string password,
    std::string command,
    std::chrono::milliseconds connect_timeout,
    size_t max_output_bytes,
    std::string expected_fingerprint,
    bool take_pending_transport)
    : host_(std::move(host)),
      port_(port),
      username_(std::move(username)),
      password_(std::move(password)),
      command_(std::move(command)),
      expected_fingerprint_(std::move(expected_fingerprint)),
      take_pending_transport_(take_pending_transport),
      deadline_(MonoClock::now() + connect_timeout),
      max_output_bytes_(max_output_bytes) {
    if (host_.empty() || username_.empty() || password_.empty() || command_.empty()) {
        throw std::invalid_argument("host/username/password/command must not be empty");
    }
    if (port_ == 0) throw std::invalid_argument("port must not be zero");
    if (connect_timeout.count() <= 0) {
        throw std::invalid_argument("connect_timeout must be positive");
    }

    if (!take_pending_transport_) {
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
    LoopContext& context,
    const ReadySet& ready,
    MonoTime now) {
    if (take_pending_transport_ && fd_ < 0) {
        fd_ = context.takeTransportFd();
        if (fd_ < 0) {
            return StepResult::failed(SshError{
                ErrorDomain::kInternal, "no_transport_fd",
                "operation requires an established transport socket"});
        }
        connected_ = true;
    }
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

    if (handshake_done_ && !hostkey_checked_) {
        hostkey_checked_ = true;
        if (!expected_fingerprint_.empty()) {
            int key_type = 0;
            const std::vector<uint8_t> blob = session_.hostKey(&key_type);
            const std::string fingerprint = hostKeySha256Fingerprint(blob);
            if (fingerprint != expected_fingerprint_) {
                SshError error;
                error.domain = ErrorDomain::kHostKey;
                error.code = "host_key_mismatch";
                error.message = "host key does not match expected fingerprint";
                return StepResult::failed(std::move(error));
            }
        }
    }

    // Phase 3: password or keyboard-interactive auth.
    if (!auth_done_) {
        if (!auth_method_decided_) {
            Libssh2CallResult methods_result = classifyLibssh2Pointer(
                session_.get(), fd_,
                libssh2_userauth_list(
                    session_.get(), username_.c_str(),
                    static_cast<unsigned int>(username_.size())),
                ErrorDomain::kAuth, "userauth_list");
            switch (methods_result.kind) {
                case Libssh2CallKind::kWouldBlock:
                    return StepResult::waitIo(
                        std::move(methods_result.interest));
                case Libssh2CallKind::kFailed:
                    return StepResult::failed(
                        std::move(methods_result.error));
                case Libssh2CallKind::kSucceeded:
                    break;
            }
            const char* methods =
                static_cast<const char*>(methods_result.pointer);
            auth_method_decided_ = true;
            if (hasAuthMethod(methods, "password")) {
                use_keyboard_interactive_ = false;
            } else if (hasAuthMethod(methods, "keyboard-interactive")) {
                use_keyboard_interactive_ = true;
            } else {
                SshError error;
                error.domain = ErrorDomain::kAuth;
                error.code = "no_supported_password_method";
                error.message = "server does not offer password or keyboard-interactive";
                return StepResult::failed(std::move(error));
            }
        }

        if (!auth_started_) {
            auth_started_ = true;
            if (use_keyboard_interactive_) {
                void** abstract_slot = libssh2_session_abstract(session_.get());
                if (abstract_slot != nullptr) {
                    *abstract_slot = const_cast<std::string*>(&password_);
                }
            }
        }

        const int result = use_keyboard_interactive_
            ? libssh2_userauth_keyboard_interactive(
                  session_.get(), username_.c_str(), keyboardInteractiveCallback)
            : libssh2_userauth_password(
                  session_.get(), username_.c_str(), password_.c_str());
        Libssh2CallResult translated = classifyLibssh2Int(
            session_.get(), fd_, result, ErrorDomain::kAuth,
            use_keyboard_interactive_ ? "userauth_keyboard_interactive"
                                      : "userauth_password");
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

    // Phase 6: read stdout and stderr until channel EOF.
    if (!close_started_) {
        if (libssh2_channel_eof(channel_)) {
            close_started_ = true;
        } else {
            char out_buffer[4096];
            char err_buffer[4096];
            IoInterest pending_interest;
            size_t progress_bytes = 0;

            const ssize_t out_count = libssh2_channel_read(
                channel_, out_buffer, sizeof(out_buffer));
            Libssh2CallResult out_translated = classifyLibssh2Count(
                session_.get(), fd_, out_count, ErrorDomain::kChannel,
                "channel_read");
            switch (out_translated.kind) {
                case Libssh2CallKind::kWouldBlock:
                    pending_interest = std::move(out_translated.interest);
                    break;
                case Libssh2CallKind::kFailed:
                    return StepResult::failed(std::move(out_translated.error));
                case Libssh2CallKind::kSucceeded:
                    if (out_translated.value > 0) {
                        output_.append(
                            out_buffer,
                            static_cast<size_t>(out_translated.value));
                        progress_bytes += static_cast<size_t>(out_translated.value);
                    }
                    break;
            }

            const ssize_t err_count = libssh2_channel_read_stderr(
                channel_, err_buffer, sizeof(err_buffer));
            Libssh2CallResult err_translated = classifyLibssh2Count(
                session_.get(), fd_, err_count, ErrorDomain::kChannel,
                "channel_read_stderr");
            switch (err_translated.kind) {
                case Libssh2CallKind::kWouldBlock:
                    if (pending_interest.empty()) {
                        pending_interest = std::move(err_translated.interest);
                    }
                    break;
                case Libssh2CallKind::kFailed:
                    return StepResult::failed(std::move(err_translated.error));
                case Libssh2CallKind::kSucceeded:
                    if (err_translated.value > 0) {
                        stderr_.append(
                            err_buffer,
                            static_cast<size_t>(err_translated.value));
                        progress_bytes += static_cast<size_t>(err_translated.value);
                    }
                    break;
            }

            if (progress_bytes > 0) {
                if (output_.size() + stderr_.size() > max_output_bytes_) {
                    output_limit_hit_ = true;
                    close_started_ = true;
                }
                return StepResult::progress(progress_bytes);
            }
            if (!pending_interest.empty()) {
                return StepResult::waitIo(std::move(pending_interest));
            }
            if (libssh2_channel_eof(channel_)) {
                close_started_ = true;
            } else {
                return StepResult::noProgress();
            }
        }
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
    const int exit_code = output_limit_hit_
        ? 125
        : libssh2_channel_get_exit_status(channel_);
    libssh2_channel_free(channel_);
    channel_ = nullptr;
    std::string result_payload =
        "exit=" + std::to_string(exit_code) + "\n" + output_;
    if (!stderr_.empty()) {
        result_payload += "\nSTDERR_BEGIN\n" + stderr_ + "\nSTDERR_END\n";
    }
    return StepResult::complete(std::move(result_payload));
}


TcpPrivateKeyExecOperation::TcpPrivateKeyExecOperation(
    std::string host,
    uint16_t port,
    std::string username,
    std::string private_key,
    std::string passphrase,
    std::string command,
    std::chrono::milliseconds connect_timeout,
    size_t max_output_bytes,
    std::string expected_fingerprint,
    bool take_pending_transport)
    : host_(std::move(host)),
      port_(port),
      username_(std::move(username)),
      private_key_(std::move(private_key)),
      passphrase_(std::move(passphrase)),
      command_(std::move(command)),
      expected_fingerprint_(std::move(expected_fingerprint)),
      take_pending_transport_(take_pending_transport),
      deadline_(MonoClock::now() + connect_timeout),
      max_output_bytes_(max_output_bytes) {
    if (host_.empty() || username_.empty() || private_key_.empty() || command_.empty()) {
        throw std::invalid_argument("host/username/private key/command must not be empty");
    }
    if (port_ == 0) throw std::invalid_argument("port must not be zero");
    if (connect_timeout.count() <= 0) {
        throw std::invalid_argument("connect_timeout must be positive");
    }

    if (!take_pending_transport_) {
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
    LoopContext& context,
    const ReadySet& ready,
    MonoTime now) {
    if (take_pending_transport_ && fd_ < 0) {
        fd_ = context.takeTransportFd();
        if (fd_ < 0) {
            return StepResult::failed(SshError{
                ErrorDomain::kInternal, "no_transport_fd",
                "operation requires an established transport socket"});
        }
        connected_ = true;
    }
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

    if (handshake_done_ && !hostkey_checked_) {
        hostkey_checked_ = true;
        if (!expected_fingerprint_.empty()) {
            int key_type = 0;
            const std::vector<uint8_t> blob = session_.hostKey(&key_type);
            const std::string fingerprint = hostKeySha256Fingerprint(blob);
            if (fingerprint != expected_fingerprint_) {
                SshError error;
                error.domain = ErrorDomain::kHostKey;
                error.code = "host_key_mismatch";
                error.message = "host key does not match expected fingerprint";
                return StepResult::failed(std::move(error));
            }
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
        if (libssh2_channel_eof(channel_)) {
            close_started_ = true;
        } else {
            char out_buffer[4096];
            char err_buffer[4096];
            IoInterest pending_interest;
            size_t progress_bytes = 0;

            const ssize_t out_count = libssh2_channel_read(
                channel_, out_buffer, sizeof(out_buffer));
            Libssh2CallResult out_translated = classifyLibssh2Count(
                session_.get(), fd_, out_count, ErrorDomain::kChannel,
                "channel_read");
            switch (out_translated.kind) {
                case Libssh2CallKind::kWouldBlock:
                    pending_interest = std::move(out_translated.interest);
                    break;
                case Libssh2CallKind::kFailed:
                    return StepResult::failed(std::move(out_translated.error));
                case Libssh2CallKind::kSucceeded:
                    if (out_translated.value > 0) {
                        output_.append(
                            out_buffer,
                            static_cast<size_t>(out_translated.value));
                        progress_bytes += static_cast<size_t>(out_translated.value);
                    }
                    break;
            }

            const ssize_t err_count = libssh2_channel_read_stderr(
                channel_, err_buffer, sizeof(err_buffer));
            Libssh2CallResult err_translated = classifyLibssh2Count(
                session_.get(), fd_, err_count, ErrorDomain::kChannel,
                "channel_read_stderr");
            switch (err_translated.kind) {
                case Libssh2CallKind::kWouldBlock:
                    if (pending_interest.empty()) {
                        pending_interest = std::move(err_translated.interest);
                    }
                    break;
                case Libssh2CallKind::kFailed:
                    return StepResult::failed(std::move(err_translated.error));
                case Libssh2CallKind::kSucceeded:
                    if (err_translated.value > 0) {
                        stderr_.append(
                            err_buffer,
                            static_cast<size_t>(err_translated.value));
                        progress_bytes += static_cast<size_t>(err_translated.value);
                    }
                    break;
            }

            if (progress_bytes > 0) {
                if (output_.size() + stderr_.size() > max_output_bytes_) {
                    output_limit_hit_ = true;
                    close_started_ = true;
                }
                return StepResult::progress(progress_bytes);
            }
            if (!pending_interest.empty()) {
                return StepResult::waitIo(std::move(pending_interest));
            }
            if (libssh2_channel_eof(channel_)) {
                close_started_ = true;
            } else {
                return StepResult::noProgress();
            }
        }
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
    const int exit_code = output_limit_hit_
        ? 125
        : libssh2_channel_get_exit_status(channel_);
    libssh2_channel_free(channel_);
    channel_ = nullptr;
    std::string result_payload =
        "exit=" + std::to_string(exit_code) + "\n" + output_;
    if (!stderr_.empty()) {
        result_payload += "\nSTDERR_BEGIN\n" + stderr_ + "\nSTDERR_END\n";
    }
    return StepResult::complete(std::move(result_payload));
}
} // namespace sshnative

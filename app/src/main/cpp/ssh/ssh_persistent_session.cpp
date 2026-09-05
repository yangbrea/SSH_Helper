#include "ssh_persistent_session.h"

#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <utility>
#include <vector>

#include "ssh_error.h"
#include "ssh_hostkey.h"
#include "ssh_libssh2_nonblocking.h"
#include "ssh_socket.h"

namespace sshnative {

namespace {

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

SshError noSessionError() {
    SshError error;
    error.domain = ErrorDomain::kInternal;
    error.code = "no_active_session";
    error.message = "no active authenticated SSH session";
    return error;
}

} // namespace

SshSessionResource::SshSessionResource(
    std::unique_ptr<Libssh2Session> session,
    int fd)
    : session_(std::move(session)), fd_(fd) {
    if (!session_ || fd_ < 0) {
        throw std::invalid_argument("invalid active session resource");
    }
}

SshSessionResource::~SshSessionResource() {
    forceClose();
}

StepResult SshSessionResource::closeStep(const ReadySet&, MonoTime) {
    forceClose();
    return StepResult::complete();
}

void SshSessionResource::forceClose() noexcept {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
    if (session_) {
        session_.reset();
    }
}

OpenAuthenticatedSessionOperation::OpenAuthenticatedSessionOperation(
    std::string username,
    std::string password,
    std::string private_key,
    std::string passphrase,
    std::string expected_fingerprint)
    : username_(std::move(username)),
      password_(std::move(password)),
      private_key_(std::move(private_key)),
      passphrase_(std::move(passphrase)),
      expected_fingerprint_(std::move(expected_fingerprint)) {
    if (username_.empty()) {
        throw std::invalid_argument("username must not be empty");
    }
    if (password_.empty() && private_key_.empty()) {
        throw std::invalid_argument("password or private key must not be empty");
    }
}

OpenAuthenticatedSessionOperation::~OpenAuthenticatedSessionOperation() {
    if (fd_ >= 0) {
        closeFd(fd_);
        fd_ = -1;
    }
}

StepResult OpenAuthenticatedSessionOperation::step(
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

    if (!session_) {
        session_ = std::make_unique<Libssh2Session>();
        session_->setBlocking(false);
    }

    if (!handshake_done_) {
        const int result = libssh2_session_handshake(session_->get(), fd_);
        Libssh2CallResult translated = classifyLibssh2Int(
            session_->get(), fd_, result, ErrorDomain::kSshHandshake,
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
            const std::vector<uint8_t> blob = session_->hostKey(&key_type);
            if (hostKeySha256Fingerprint(blob) != expected_fingerprint_) {
                SshError error;
                error.domain = ErrorDomain::kHostKey;
                error.code = "host_key_mismatch";
                error.message = "host key does not match expected fingerprint";
                return StepResult::failed(std::move(error));
            }
        }
    }

    if (!auth_done_) {
        if (usePrivateKey()) {
            if (!auth_started_) auth_started_ = true;
            const int result = libssh2_userauth_publickey_frommemory(
                session_->get(),
                username_.c_str(),
                static_cast<unsigned int>(username_.size()),
                nullptr,
                0,
                private_key_.data(),
                private_key_.size(),
                passphrase_.empty() ? nullptr : passphrase_.c_str());
            Libssh2CallResult translated = classifyLibssh2Int(
                session_->get(), fd_, result, ErrorDomain::kAuth,
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
        } else {
            if (!auth_method_decided_) {
                Libssh2CallResult methods_result = classifyLibssh2Pointer(
                    session_->get(), fd_,
                    libssh2_userauth_list(
                        session_->get(), username_.c_str(),
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
                    void** abstract_slot = libssh2_session_abstract(session_->get());
                    if (abstract_slot != nullptr) {
                        *abstract_slot = const_cast<std::string*>(&password_);
                    }
                }
            }

            const int result = use_keyboard_interactive_
                ? libssh2_userauth_keyboard_interactive(
                      session_->get(), username_.c_str(), keyboardInteractiveCallback)
                : libssh2_userauth_password(
                      session_->get(), username_.c_str(), password_.c_str());
            Libssh2CallResult translated = classifyLibssh2Int(
                session_->get(), fd_, result, ErrorDomain::kAuth,
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
    }

    if (auth_done_) {
        auto resource = std::make_unique<SshSessionResource>(
            std::move(session_), fd_);
        fd_ = -1;
        context.storeActiveSession(std::move(resource));
        return StepResult::complete("session=ok");
    }

    return StepResult::noProgress();
}

PersistentExecOperation::PersistentExecOperation(
    std::string command,
    size_t max_output_bytes)
    : command_(std::move(command)),
      max_output_bytes_(max_output_bytes) {
    if (command_.empty()) {
        throw std::invalid_argument("command must not be empty");
    }
    if (max_output_bytes_ == 0) {
        throw std::invalid_argument("max output bytes must be positive");
    }
}

PersistentExecOperation::~PersistentExecOperation() {
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
}

StepResult PersistentExecOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) {
        return StepResult::failed(noSessionError());
    }

    if (channel_ == nullptr) {
        Libssh2CallResult translated = classifyLibssh2Pointer(
            session, fd, libssh2_channel_open_session(session),
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
            session, fd, result, ErrorDomain::kChannel, "channel_exec");
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
                session, fd, out_count, ErrorDomain::kChannel, "channel_read");
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
                session, fd, err_count, ErrorDomain::kChannel,
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

    if (close_started_ && !wait_closed_done_) {
        if (!eof_sent_) {
            const int eof_result = libssh2_channel_send_eof(channel_);
            Libssh2CallResult eof_translated = classifyLibssh2Int(
                session, fd, eof_result, ErrorDomain::kChannel,
                "channel_send_eof");
            switch (eof_translated.kind) {
                case Libssh2CallKind::kWouldBlock:
                    return StepResult::waitIo(std::move(eof_translated.interest));
                case Libssh2CallKind::kFailed:
                    return StepResult::failed(std::move(eof_translated.error));
                case Libssh2CallKind::kSucceeded:
                    eof_sent_ = true;
                    break;
            }
        }

        const int result = libssh2_channel_close(channel_);
        Libssh2CallResult translated = classifyLibssh2Int(
            session, fd, result, ErrorDomain::kChannel, "channel_close");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                break;
        }

        const int wait_result = libssh2_channel_wait_closed(channel_);
        Libssh2CallResult wait_translated = classifyLibssh2Int(
            session, fd, wait_result, ErrorDomain::kChannel,
            "channel_wait_closed");
        switch (wait_translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(wait_translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(wait_translated.error));
            case Libssh2CallKind::kSucceeded:
                wait_closed_done_ = true;
                break;
        }
    }

    if (close_started_ && wait_closed_done_) {
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

    return StepResult::noProgress();
}

} // namespace sshnative

#include "ssh_jump_operation.h"

#include <cerrno>
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

void jumpKeyboardInteractiveCallback(
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
    auto* tunnel = static_cast<JumpTunnelTransport*>(*abstract);
    if (tunnel->keyboard_password == nullptr) return;
    const std::string* password = tunnel->keyboard_password;
    char* copy = static_cast<char*>(std::malloc(password->size() + 1));
    if (copy == nullptr) return;
    std::memcpy(copy, password->data(), password->size());
    copy[password->size()] = '\0';
    responses[0].text = copy;
    responses[0].length = static_cast<unsigned int>(password->size());
}

ssize_t jumpTunnelSend(
    libssh2_socket_t,
    const void* buffer,
    size_t length,
    int,
    void** abstract) {
    auto* tunnel = static_cast<JumpTunnelTransport*>(*abstract);
    if (tunnel == nullptr || tunnel->channel == nullptr) return -1;
    const ssize_t result = libssh2_channel_write(
        tunnel->channel, static_cast<const char*>(buffer), length);
    if (result >= 0) return result;
    if (result == LIBSSH2_ERROR_EAGAIN) return -EAGAIN;
    return -1;
}

ssize_t jumpTunnelRecv(
    libssh2_socket_t,
    void* buffer,
    size_t length,
    int,
    void** abstract) {
    auto* tunnel = static_cast<JumpTunnelTransport*>(*abstract);
    if (tunnel == nullptr || tunnel->channel == nullptr) return -1;
    const ssize_t result = libssh2_channel_read(
        tunnel->channel, static_cast<char*>(buffer), length);
    if (result >= 0) return result;
    if (result == LIBSSH2_ERROR_EAGAIN) return -EAGAIN;
    return -1;
}

SshError noJumpSessionError() {
    SshError error;
    error.domain = ErrorDomain::kInternal;
    error.code = "no_jump_session";
    error.message = "no authenticated jump session available";
    return error;
}

} // namespace

OpenJumpTargetSessionOperation::OpenJumpTargetSessionOperation(
    std::string target_host,
    uint16_t target_port,
    std::string username,
    std::string password,
    std::string private_key,
    std::string passphrase,
    std::string expected_fingerprint)
    : target_host_(std::move(target_host)),
      target_port_(target_port),
      username_(std::move(username)),
      password_(std::move(password)),
      private_key_(std::move(private_key)),
      passphrase_(std::move(passphrase)),
      expected_fingerprint_(std::move(expected_fingerprint)) {
    if (target_host_.empty() || target_port_ == 0) {
        throw std::invalid_argument("target host/port must not be empty");
    }
    if (username_.empty()) {
        throw std::invalid_argument("username must not be empty");
    }
    if (password_.empty() && private_key_.empty()) {
        throw std::invalid_argument("password or private key must not be empty");
    }
}

OpenJumpTargetSessionOperation::~OpenJumpTargetSessionOperation() {
    tunnel_.reset();
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
}

StepResult OpenJumpTargetSessionOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* jump_resource_ptr = context.jumpSession();
    if (jump_resource_ptr == nullptr ||
        jump_resource_ptr->kind() != ResourceKind::kJumpSession) {
        return StepResult::failed(noJumpSessionError());
    }
    auto* jump_resource = static_cast<SshSessionResource*>(jump_resource_ptr);
    LIBSSH2_SESSION* jump_session = jump_resource->session()->get();
    const int jump_fd = jump_resource->fd();
    jump_fd_ = jump_fd;

    if (channel_ == nullptr) {
        channel_started_ = true;
        Libssh2CallResult translated = classifyLibssh2Pointer(
            jump_session, jump_fd,
            libssh2_channel_direct_tcpip_ex(
                jump_session, target_host_.c_str(), target_port_,
                "127.0.0.1", 0),
            ErrorDomain::kChannel, "channel_direct_tcpip");
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

    if (channel_ != nullptr && !session_) {
        tunnel_ = std::make_shared<JumpTunnelTransport>();
        tunnel_->jump_session = jump_session;
        tunnel_->channel = channel_;
        tunnel_->jump_fd = jump_fd;
        session_ = std::make_unique<Libssh2Session>(tunnel_.get());
        session_->setBlocking(false);
        session_->setCustomIo(&jumpTunnelSend, &jumpTunnelRecv);
    }

    if (session_ && !handshake_done_) {
        const int result = libssh2_session_handshake(session_->get(), jump_fd);
        Libssh2CallResult translated = classifyLibssh2Int(
            session_->get(), jump_fd, result, ErrorDomain::kSshHandshake,
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

    if (handshake_done_ && !auth_done_) {
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
                session_->get(), jump_fd, result, ErrorDomain::kAuth,
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
                    session_->get(), jump_fd,
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
                if (use_keyboard_interactive_ && tunnel_) {
                    tunnel_->keyboard_password = &password_;
                }
            }

            const int result = use_keyboard_interactive_
                ? libssh2_userauth_keyboard_interactive(
                      session_->get(), username_.c_str(),
                      jumpKeyboardInteractiveCallback)
                : libssh2_userauth_password(
                      session_->get(), username_.c_str(), password_.c_str());
            Libssh2CallResult translated = classifyLibssh2Int(
                session_->get(), jump_fd, result, ErrorDomain::kAuth,
                use_keyboard_interactive_ ? "userauth_keyboard_interactive"
                                          : "userauth_password");
            switch (translated.kind) {
                case Libssh2CallKind::kWouldBlock:
                    return StepResult::waitIo(std::move(translated.interest));
                case Libssh2CallKind::kFailed:
                    if (tunnel_) tunnel_->keyboard_password = nullptr;
                    return StepResult::failed(std::move(translated.error));
                case Libssh2CallKind::kSucceeded:
                    if (tunnel_) tunnel_->keyboard_password = nullptr;
                    auth_done_ = true;
                    break;
            }
        }
    }

    if (auth_done_) {
        auto resource = std::make_unique<SshSessionResource>(
            std::move(session_), jump_fd,
            ResourceKind::kLibssh2Session,
            /*owns_fd=*/false);
        resource->setJumpTunnel(tunnel_);
        channel_ = nullptr; // ownership moved to resource's tunnel
        tunnel_.reset();
        context.storeActiveSession(std::move(resource));
        return StepResult::complete("session=ok");
    }

    return StepResult::noProgress();
}

OpenJumpTargetHandshakeOperation::OpenJumpTargetHandshakeOperation(
    std::string target_host,
    uint16_t target_port,
    bool hold_pending)
    : target_host_(std::move(target_host)),
      target_port_(target_port),
      hold_pending_(hold_pending) {
    if (target_host_.empty() || target_port_ == 0) {
        throw std::invalid_argument("target host/port must not be empty");
    }
}

OpenJumpTargetHandshakeOperation::~OpenJumpTargetHandshakeOperation() {
    tunnel_.reset();
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
}

StepResult OpenJumpTargetHandshakeOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* jump_resource_ptr = context.jumpSession();
    if (jump_resource_ptr == nullptr ||
        jump_resource_ptr->kind() != ResourceKind::kJumpSession) {
        return StepResult::failed(noJumpSessionError());
    }
    auto* jump_resource = static_cast<SshSessionResource*>(jump_resource_ptr);
    LIBSSH2_SESSION* jump_session = jump_resource->session()->get();
    const int jump_fd = jump_resource->fd();
    jump_fd_ = jump_fd;

    if (channel_ == nullptr) {
        Libssh2CallResult translated = classifyLibssh2Pointer(
            jump_session, jump_fd,
            libssh2_channel_direct_tcpip_ex(
                jump_session, target_host_.c_str(), target_port_,
                "127.0.0.1", 0),
            ErrorDomain::kChannel, "channel_direct_tcpip");
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

    if (channel_ != nullptr && !session_) {
        tunnel_ = std::make_shared<JumpTunnelTransport>();
        tunnel_->jump_session = jump_session;
        tunnel_->channel = channel_;
        tunnel_->jump_fd = jump_fd;
        session_ = std::make_unique<Libssh2Session>(tunnel_.get());
        session_->setBlocking(false);
        session_->setCustomIo(&jumpTunnelSend, &jumpTunnelRecv);
    }

    if (session_ && !handshake_done_) {
        const int result = libssh2_session_handshake(session_->get(), jump_fd);
        Libssh2CallResult translated = classifyLibssh2Int(
            session_->get(), jump_fd, result, ErrorDomain::kSshHandshake,
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

    if (handshake_done_) {
        int key_type = 0;
        const std::vector<uint8_t> blob = session_->hostKey(&key_type);
        if (blob.empty()) {
            SshError error;
            error.domain = ErrorDomain::kHostKey;
            error.code = "host_key_unavailable";
            error.message = "handshake succeeded but host key was not available";
            return StepResult::failed(std::move(error));
        }
        if (hold_pending_) {
            auto resource = std::make_unique<SshSessionResource>(
                std::move(session_), jump_fd_,
                ResourceKind::kLibssh2Session,
                /*owns_fd=*/false);
            resource->setJumpTunnel(tunnel_);
            channel_ = nullptr;
            tunnel_.reset();
            context.storePendingSession(std::move(resource));
        }
        return StepResult::complete(
            "fingerprint=" + hostKeySha256Fingerprint(blob) +
            "\nkeyType=" + hostKeyTypeName(key_type) +
            "\nkeyBase64=" + hostKeyBase64(blob));
    }

    return StepResult::noProgress();
}

} // namespace sshnative

#include "ssh_keepalive_operation.h"

#include "ssh_error.h"
#include "ssh_libssh2_nonblocking.h"
#include "ssh_persistent_session.h"

#include <poll.h>
#include <stdexcept>
#include <vector>

namespace sshnative {

namespace {

SshError keepaliveFailure(const std::string& code, const std::string& message) {
    SshError error;
    error.domain = ErrorDomain::kSystem;
    error.code = code;
    error.message = message;
    return error;
}

} // namespace

KeepaliveOperation::KeepaliveOperation(std::chrono::milliseconds response_timeout)
    : response_timeout_(response_timeout) {
    if (response_timeout_.count() <= 0) {
        throw std::invalid_argument("keepalive response timeout must be positive");
    }
}

StepResult KeepaliveOperation::step(
    LoopContext& context,
    const ReadySet& ready,
    MonoTime now) {
    (void)now;
    RuntimeResource* active_resource = context.activeSession();
    RuntimeResource* jump_resource = context.jumpSession();

    if (active_resource == nullptr && jump_resource == nullptr) {
        return StepResult::failed(keepaliveFailure(
            "no_active_session", "no SSH session is available for keepalive"));
    }

    if (!waiting_reply_) {
        IoInterest send_interest;

        auto sendOn = [&](RuntimeResource* resource, int* stored_fd) -> bool {
            if (resource == nullptr) return true;
            if (resource->kind() != ResourceKind::kLibssh2Session &&
                resource->kind() != ResourceKind::kJumpSession) {
                return false;
            }
            auto* session_resource = static_cast<SshSessionResource*>(resource);
            LIBSSH2_SESSION* session = session_resource->session()->get();
            const int fd = session_resource->fd();
            *stored_fd = fd;

            libssh2_keepalive_config(session, /*want_reply=*/1, /*interval=*/5);
            int seconds_to_next = 0;
            const int result = libssh2_keepalive_send(session, &seconds_to_next);
            Libssh2CallResult translated = classifyLibssh2Int(
                session, fd, result, ErrorDomain::kSystem, "keepalive_send");
            switch (translated.kind) {
                case Libssh2CallKind::kWouldBlock:
                    for (const auto& interest : translated.interest) {
                        bool exists = false;
                        for (const auto& existing : send_interest) {
                            if (existing.fd == interest.fd) exists = true;
                        }
                        if (!exists) send_interest.push_back(interest);
                    }
                    return true;
                case Libssh2CallKind::kFailed:
                    // keepalive_send uses a non-domain error; keep the structured
                    // error from the adapter when available.
                    translated.error.code = translated.error.code.empty()
                        ? "keepalive_send_failed" : translated.error.code;
                    return false;
                case Libssh2CallKind::kSucceeded:
                    return true;
            }
            return false;
        };

        if (!sendOn(active_resource, &active_fd_)) {
            return StepResult::failed(keepaliveFailure(
                "keepalive_send_failed", "keepalive send failed on active session"));
        }
        if (!sendOn(jump_resource, &jump_fd_)) {
            return StepResult::failed(keepaliveFailure(
                "keepalive_send_failed", "keepalive send failed on jump session"));
        }

        if (!send_interest.empty()) {
            return StepResult::waitIo(std::move(send_interest));
        }

        waiting_reply_ = true;
    }

    if (active_fd_ >= 0 && ready.errored(active_fd_)) {
        return StepResult::failed(keepaliveFailure(
            "transport_closed", "SSH transport closed while sending keepalive"));
    }
    if (jump_fd_ >= 0 && ready.errored(jump_fd_)) {
        return StepResult::failed(keepaliveFailure(
            "transport_closed", "jump SSH transport closed while sending keepalive"));
    }

    const bool active_ready = active_fd_ < 0 || ready.ready(active_fd_, POLLIN);
    const bool jump_ready = jump_fd_ < 0 || ready.ready(jump_fd_, POLLIN);
    if (active_ready && jump_ready) {
        waiting_reply_ = false;
        active_fd_ = -1;
        jump_fd_ = -1;
        return StepResult::complete("keepalive=ok");
    }

    IoInterest interest;
    if (active_fd_ >= 0) interest.push_back({active_fd_, POLLIN});
    if (jump_fd_ >= 0) interest.push_back({jump_fd_, POLLIN});
    return StepResult::waitIo(std::move(interest));
}

} // namespace sshnative

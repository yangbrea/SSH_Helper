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

KeepaliveOperation::KeepaliveOperation(std::chrono::milliseconds send_timeout) {
    if (send_timeout.count() <= 0) {
        throw std::invalid_argument("keepalive send timeout must be positive");
    }
}

StepResult KeepaliveOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* resources[] = {context.activeSession(), context.jumpSession()};
    if (resources[0] == nullptr && resources[1] == nullptr) {
        return StepResult::failed(keepaliveFailure(
            "no_active_session", "no SSH session is available for keepalive"));
    }

    IoInterest send_interest;
    for (RuntimeResource* resource : resources) {
        if (resource == nullptr) continue;
        if (resource->kind() != ResourceKind::kLibssh2Session &&
            resource->kind() != ResourceKind::kJumpSession) {
            return StepResult::failed(keepaliveFailure(
                "no_active_session", "invalid SSH session for keepalive"));
        }
        auto* session_resource = static_cast<SshSessionResource*>(resource);
        LIBSSH2_SESSION* session = session_resource->session()->get();
        const int fd = session_resource->fd();

        // Inspect close/error signals without consuming bytes owned by libssh2.
        // RDHUP also detects a FIN behind buffered SSH disconnect data.
        short closed_events = POLLHUP | POLLERR | POLLNVAL;
#ifdef POLLRDHUP
        closed_events |= POLLRDHUP;
#endif
        pollfd descriptor{fd, closed_events, 0};
        if (::poll(&descriptor, 1, 0) > 0 && (descriptor.revents & closed_events)) {
            return StepResult::failed(keepaliveFailure(
                "transport_closed", "SSH transport closed while sending keepalive"));
        }

        // libssh2_keepalive_send is a send-only API. Success includes a no-op
        // inside the configured interval (and a full outbound buffer). It does
        // not expose an acknowledged ping; waiting for POLLIN invents a reply
        // contract and races shell/SFTP reads. Send without requesting a reply
        // so idle sessions do not accumulate unconsumed global-request replies.
        libssh2_keepalive_config(session, /*want_reply=*/0, /*interval=*/5);
        int seconds_to_next = 0;
        const int result = libssh2_keepalive_send(session, &seconds_to_next);
        auto translated = classifyLibssh2Int(
            session, fd, result, ErrorDomain::kSystem, "keepalive_send");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                send_interest.insert(send_interest.end(), translated.interest.begin(),
                                     translated.interest.end());
                break;
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                break;
        }
    }
    if (!send_interest.empty()) return StepResult::waitIo(std::move(send_interest));
    return StepResult::complete("keepalive=ok");
}

} // namespace sshnative

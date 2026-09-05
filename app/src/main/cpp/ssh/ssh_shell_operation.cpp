#include "ssh_shell_operation.h"

#include <stdexcept>
#include <utility>

#include "ssh_error.h"
#include "ssh_libssh2_nonblocking.h"
#include "ssh_persistent_session.h"

namespace sshnative {

namespace {

SshError noSessionError() {
    SshError error;
    error.domain = ErrorDomain::kInternal;
    error.code = "no_active_session";
    error.message = "no active authenticated SSH session";
    return error;
}

SshError noChannelError() {
    SshError error;
    error.domain = ErrorDomain::kInternal;
    error.code = "no_active_channel";
    error.message = "no active shell channel";
    return error;
}

} // namespace

SshChannelResource::SshChannelResource(
    LIBSSH2_SESSION* session,
    int fd,
    LIBSSH2_CHANNEL* channel)
    : session_(session), fd_(fd), channel_(channel) {
    if (session_ == nullptr || fd_ < 0 || channel_ == nullptr) {
        throw std::invalid_argument("invalid channel resource");
    }
}

SshChannelResource::~SshChannelResource() {
    forceClose();
}

StepResult SshChannelResource::closeStep(const ReadySet&, MonoTime) {
    if (channel_ == nullptr) return StepResult::complete();
    if (!eof_sent_) {
        const int result = libssh2_channel_send_eof(channel_);
        Libssh2CallResult translated = classifyLibssh2Int(
            session_, fd_, result, ErrorDomain::kChannel, "channel_send_eof");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                eof_sent_ = true;
                break;
            case Libssh2CallKind::kSucceeded:
                eof_sent_ = true;
                break;
        }
    }
    if (!close_started_) {
        const int result = libssh2_channel_close(channel_);
        Libssh2CallResult translated = classifyLibssh2Int(
            session_, fd_, result, ErrorDomain::kChannel, "channel_close");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                close_started_ = true;
                break;
            case Libssh2CallKind::kSucceeded:
                close_started_ = true;
                break;
        }
    }
    if (close_started_) {
        const int result = libssh2_channel_wait_closed(channel_);
        Libssh2CallResult translated = classifyLibssh2Int(
            session_, fd_, result, ErrorDomain::kChannel, "channel_wait_closed");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
            case Libssh2CallKind::kSucceeded:
                break;
        }
        libssh2_channel_free(channel_);
        channel_ = nullptr;
        return StepResult::complete();
    }
    return StepResult::noProgress();
}

void SshChannelResource::forceClose() noexcept {
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
}

OpenShellOperation::OpenShellOperation(
    unsigned int columns,
    unsigned int rows,
    std::string term)
    : columns_(columns), rows_(rows), term_(std::move(term)) {
    if (term_.empty()) term_ = "xterm-256color";
    if (columns_ == 0) columns_ = 80;
    if (rows_ == 0) rows_ = 24;
}

OpenShellOperation::~OpenShellOperation() {
    if (channel_ != nullptr) {
        libssh2_channel_free(channel_);
        channel_ = nullptr;
    }
}

StepResult OpenShellOperation::step(
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
    if (fd < 0) return StepResult::failed(noSessionError());

    if (stored_) return StepResult::complete("shell=ok");
    if (channel_ == nullptr) {
        Libssh2CallResult open_result = classifyLibssh2Pointer(
            session, fd, libssh2_channel_open_session(session),
            ErrorDomain::kChannel, "channel_open_session");
        switch (open_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(open_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(open_result.error));
            case Libssh2CallKind::kSucceeded:
                channel_ = static_cast<LIBSSH2_CHANNEL*>(open_result.pointer);
                break;
        }
    }

    if (channel_ != nullptr && !pty_requested_) {
        const int result = libssh2_channel_request_pty_ex(
            channel_, term_.data(), static_cast<unsigned int>(term_.size()),
            nullptr, 0, static_cast<int>(columns_), static_cast<int>(rows_),
            0, 0);
        Libssh2CallResult translated = classifyLibssh2Int(
            session, fd, result, ErrorDomain::kChannel, "channel_request_pty");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                libssh2_channel_free(channel_);
                channel_ = nullptr;
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                pty_requested_ = true;
                break;
        }
    }

    if (channel_ != nullptr && pty_requested_ && !shell_requested_) {
        const int result = libssh2_channel_shell(channel_);
        Libssh2CallResult translated = classifyLibssh2Int(
            session, fd, result, ErrorDomain::kChannel, "channel_shell");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                libssh2_channel_free(channel_);
                channel_ = nullptr;
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                shell_requested_ = true;
                break;
        }
    }

    if (channel_ != nullptr && pty_requested_ && shell_requested_ && !stored_) {
        auto resource = std::make_unique<SshChannelResource>(session, fd, channel_);
        channel_ = nullptr;
        context.storeActiveChannel(std::move(resource));
        stored_ = true;
        return StepResult::complete("shell=ok");
    }
    return StepResult::noProgress();
}

ShellWriteOperation::ShellWriteOperation(std::string data)
    : data_(std::move(data)) {}

ShellWriteOperation::~ShellWriteOperation() = default;

StepResult ShellWriteOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* channel_resource = context.activeChannel();
    if (channel_resource == nullptr ||
        channel_resource->kind() != ResourceKind::kChannel) {
        return StepResult::failed(noChannelError());
    }
    auto* shell = static_cast<SshChannelResource*>(channel_resource);
    LIBSSH2_SESSION* session = shell->session();
    const int fd = shell->fd();
    LIBSSH2_CHANNEL* channel = shell->channel();
    if (channel == nullptr || session == nullptr || fd < 0) {
        return StepResult::failed(noChannelError());
    }

    while (offset_ < data_.size()) {
        const ssize_t count = libssh2_channel_write(
            channel, data_.data() + offset_, data_.size() - offset_);
        Libssh2CallResult translated = classifyLibssh2Count(
            session, fd, count, ErrorDomain::kChannel, "channel_write");
        switch (translated.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(translated.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(translated.error));
            case Libssh2CallKind::kSucceeded:
                if (translated.value > 0) {
                    offset_ += static_cast<size_t>(translated.value);
                } else {
                    return StepResult::noProgress();
                }
                break;
        }
    }
    return StepResult::complete("written=" + std::to_string(offset_));
}

ShellReadOperation::ShellReadOperation(size_t max_bytes)
    : max_bytes_(max_bytes > 0 ? max_bytes : 8192) {}

ShellReadOperation::~ShellReadOperation() = default;

StepResult ShellReadOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* channel_resource = context.activeChannel();
    if (channel_resource == nullptr ||
        channel_resource->kind() != ResourceKind::kChannel) {
        return StepResult::failed(noChannelError());
    }
    auto* shell = static_cast<SshChannelResource*>(channel_resource);
    LIBSSH2_SESSION* session = shell->session();
    const int fd = shell->fd();
    LIBSSH2_CHANNEL* channel = shell->channel();
    if (channel == nullptr || session == nullptr || fd < 0) {
        return StepResult::failed(noChannelError());
    }

    char buffer[8192];
    const size_t want = max_bytes_ < sizeof(buffer) ? max_bytes_ : sizeof(buffer);
    const ssize_t count = libssh2_channel_read(channel, buffer, want);
    Libssh2CallResult translated = classifyLibssh2Count(
        session, fd, count, ErrorDomain::kChannel, "channel_read");
    switch (translated.kind) {
        case Libssh2CallKind::kWouldBlock:
            return StepResult::waitIo(std::move(translated.interest));
        case Libssh2CallKind::kFailed:
            return StepResult::failed(std::move(translated.error));
        case Libssh2CallKind::kSucceeded:
            if (translated.value > 0) {
                return StepResult::complete(
                    std::string(buffer, static_cast<size_t>(translated.value)));
            }
            if (libssh2_channel_eof(channel)) {
                return StepResult::complete(std::string());
            }
            return StepResult::noProgress();
    }
    return StepResult::noProgress();
}

ShellResizeOperation::ShellResizeOperation(unsigned int columns, unsigned int rows)
    : columns_(columns), rows_(rows) {}

ShellResizeOperation::~ShellResizeOperation() = default;

StepResult ShellResizeOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* channel_resource = context.activeChannel();
    if (channel_resource == nullptr ||
        channel_resource->kind() != ResourceKind::kChannel) {
        return StepResult::failed(noChannelError());
    }
    auto* shell = static_cast<SshChannelResource*>(channel_resource);
    const int result = libssh2_channel_request_pty_size(
        shell->channel(), static_cast<int>(columns_), static_cast<int>(rows_));
    Libssh2CallResult translated = classifyLibssh2Int(
        shell->session(), shell->fd(), result, ErrorDomain::kChannel,
        "channel_request_pty_size");
    switch (translated.kind) {
        case Libssh2CallKind::kWouldBlock:
            return StepResult::waitIo(std::move(translated.interest));
        case Libssh2CallKind::kFailed:
            return StepResult::failed(std::move(translated.error));
        case Libssh2CallKind::kSucceeded:
            return StepResult::complete("resized");
    }
    return StepResult::noProgress();
}

CloseShellOperation::CloseShellOperation() = default;
CloseShellOperation::~CloseShellOperation() = default;

StepResult CloseShellOperation::step(
    LoopContext& context,
    const ReadySet& ready,
    MonoTime) {
    RuntimeResource* channel_resource = context.activeChannel();
    if (channel_resource == nullptr ||
        channel_resource->kind() != ResourceKind::kChannel) {
        context.clearActiveChannel();
        return StepResult::complete("closed");
    }
    auto* shell = static_cast<SshChannelResource*>(channel_resource);
    StepResult result = shell->closeStep(ready, MonoTime::max());
    if (result.kind == StepKind::kComplete) {
        context.clearActiveChannel();
        return StepResult::complete("closed");
    }
    return result;
}

} // namespace sshnative

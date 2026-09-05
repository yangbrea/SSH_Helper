#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <cstddef>
#include <cstdint>
#include <string>

namespace sshnative {

// A channel opened on the active SSH session (for example a PTY shell). The
// channel remains owned by the runtime until explicitly closed or shutdown.
class SshChannelResource final : public RuntimeResource {
public:
    SshChannelResource(LIBSSH2_SESSION* session, int fd, LIBSSH2_CHANNEL* channel);
    ~SshChannelResource() override;

    ResourceKind kind() const noexcept override { return ResourceKind::kChannel; }
    StepResult closeStep(const ReadySet& ready, MonoTime now) override;
    void forceClose() noexcept override;

    LIBSSH2_CHANNEL* channel() const noexcept { return channel_; }
    LIBSSH2_SESSION* session() const noexcept { return session_; }
    int fd() const noexcept { return fd_; }

private:
    LIBSSH2_SESSION* session_ = nullptr;
    int fd_ = -1;
    LIBSSH2_CHANNEL* channel_ = nullptr;
    bool eof_sent_ = false;
    bool close_started_ = false;
};

// Opens a PTY shell (or a PTY exec command when command is non-empty) on the
// active SSH session and stores it as the runtime's active channel.
class OpenShellOperation final : public Operation {
public:
    OpenShellOperation(
        unsigned int columns,
        unsigned int rows,
        std::string term = "xterm-256color",
        std::string command = {});
    ~OpenShellOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    unsigned int columns_ = 80;
    unsigned int rows_ = 24;
    std::string term_;
    std::string command_;
    LIBSSH2_CHANNEL* channel_ = nullptr;
    bool pty_requested_ = false;
    bool startup_requested_ = false;
    bool stored_ = false;
};

// Writes bytes to the active shell channel.
class ShellWriteOperation final : public Operation {
public:
    explicit ShellWriteOperation(std::string data);
    ~ShellWriteOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    std::string data_;
    size_t offset_ = 0;
};

// Reads one chunk from the active shell channel. Completion payload contains the
// raw bytes read; an empty payload means the remote side has closed (EOF).
class ShellReadOperation final : public Operation {
public:
    explicit ShellReadOperation(size_t max_bytes = 8192);
    ~ShellReadOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    size_t max_bytes_ = 8192;
};

// Resizes the active shell PTY.
class ShellResizeOperation final : public Operation {
public:
    ShellResizeOperation(unsigned int columns, unsigned int rows);
    ~ShellResizeOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    unsigned int columns_ = 80;
    unsigned int rows_ = 24;
};

// Closes and clears the active shell channel while leaving the SSH session open.
class CloseShellOperation final : public Operation {
public:
    CloseShellOperation();
    ~CloseShellOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
};

} // namespace sshnative

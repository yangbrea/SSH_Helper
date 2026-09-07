#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <chrono>
#include <cstdint>

namespace sshnative {

// Sends SSH keepalives on the active target session and, when present, the
// jump session. After sending, it waits briefly for socket readability and
// treats POLLHUP/POLLERR as a transport disconnect. The operation is submitted
// with a deadline so a silent peer eventually surfaces as keepalive_timeout.
class KeepaliveOperation final : public Operation {
public:
    explicit KeepaliveOperation(std::chrono::milliseconds response_timeout);
    ~KeepaliveOperation() override = default;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
    CancelScope cancelScope() const noexcept override { return CancelScope::kTransport; }

private:
    std::chrono::milliseconds response_timeout_;
    bool waiting_reply_ = false;
    int active_fd_ = -1;
    int jump_fd_ = -1;
};

} // namespace sshnative

#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <chrono>
#include <cstdint>

namespace sshnative {

// Sends interval-limited SSH keepalives on target and jump sessions. This is
// outbound traffic maintenance, not an acknowledged liveness probe. Actual
// transport errors are surfaced; an operation deadline alone must not close
// the transport (the app may have been suspended in the background).
class KeepaliveOperation final : public Operation {
public:
    explicit KeepaliveOperation(std::chrono::milliseconds send_timeout);
    ~KeepaliveOperation() override = default;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;
    CancelScope cancelScope() const noexcept override { return CancelScope::kRequest; }

};

} // namespace sshnative

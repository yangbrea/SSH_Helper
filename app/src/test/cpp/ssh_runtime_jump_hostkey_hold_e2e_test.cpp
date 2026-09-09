#include "ssh/ssh_error.h"
#include "ssh/ssh_handshake_operation.h"
#include "ssh/ssh_jump_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"

#include <cassert>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <string>

using namespace std::chrono_literals;

namespace {

bool waitForEvent(sshnative::SshNativeSession& runtime,
                  sshnative::SubmitResult result,
                  sshnative::RuntimeEvent* out) {
    const auto stop = sshnative::MonoClock::now() + 10s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!runtime.waitEvent(&event, 100ms)) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != result.request_id) {
            continue;
        }
        *out = std::move(event);
        return true;
    }
    return false;
}

sshnative::RuntimeEvent submitAndWait(
    sshnative::SshNativeSession& runtime,
    std::unique_ptr<sshnative::Operation> operation) {
    const auto submit = runtime.submit(std::move(operation));
    assert(submit && "runtime must accept operation");
    sshnative::RuntimeEvent event;
    if (!waitForEvent(runtime, submit, &event)) {
        std::cerr << "operation timed out\n";
        std::exit(1);
    }
    return event;
}

void expectSuccess(const sshnative::RuntimeEvent& event, const char* what) {
    if (event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << what << " failed: "
                  << sshnative::errorDomainName(event.error.domain)
                  << " code=" << event.error.code
                  << " message=" << event.error.message << "\n";
        std::exit(1);
    }
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 3) return 64;
    const int target_port = std::atoi(argv[1]);
    const int jump_port = std::atoi(argv[2]);

    auto runtime = sshnative::createSession();

    // Hold the jump host connection while the jump host key is decided.
    auto probe_jump = submitAndWait(
        *runtime,
        std::make_unique<sshnative::TcpHandshakeOperation>(
            "127.0.0.1", static_cast<uint16_t>(jump_port), 5s,
            /*hold_pending=*/true));
    expectSuccess(probe_jump, "hold jump host key probe");

    auto auth_jump = submitAndWait(
        *runtime,
        std::make_unique<sshnative::AuthenticatePendingSessionOperation>(
            "test", "secret-jump", "", "", /*store_as_jump=*/true));
    expectSuccess(auth_jump, "authenticate held jump session");

    // Hold the target connection tunneled through the authenticated jump host.
    auto probe_target = submitAndWait(
        *runtime,
        std::make_unique<sshnative::OpenJumpTargetHandshakeOperation>(
            "127.0.0.1", static_cast<uint16_t>(target_port),
            /*hold_pending=*/true));
    expectSuccess(probe_target, "hold jump target host key probe");

    auto auth_target = submitAndWait(
        *runtime,
        std::make_unique<sshnative::AuthenticatePendingSessionOperation>(
            "test", "secret-target", "", "", /*store_as_jump=*/false));
    expectSuccess(auth_target, "authenticate held jump target session");

    auto exec = submitAndWait(
        *runtime,
        std::make_unique<sshnative::PersistentExecOperation>("test", 1024u));
    expectSuccess(exec, "persistent exec over held jump route");
    if (exec.payload.find("native-jump-poc-exec-ok") == std::string::npos) {
        std::cerr << "unexpected payload through held jump: " << exec.payload << "\n";
        return 1;
    }

    runtime->shutdown();
    std::cout << "runtime-jump-hostkey-hold-ok\n";
    return 0;
}

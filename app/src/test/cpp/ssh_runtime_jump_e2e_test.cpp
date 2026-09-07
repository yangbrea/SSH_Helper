#include "ssh/ssh_connect_operation.h"
#include "ssh/ssh_jump_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"

#include <chrono>
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <string>

using namespace std::chrono_literals;

namespace {

bool waitForEvent(sshnative::SshNativeSession& runtime,
                  sshnative::SubmitResult result,
                  sshnative::RuntimeEvent* out) {
    const auto stop = sshnative::MonoClock::now() + 8s;
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

} // namespace

int main(int argc, char** argv) {
    if (argc != 3) return 64;
    const int target_port = std::atoi(argv[1]);
    const int jump_port = std::atoi(argv[2]);

    auto runtime = sshnative::createSession();

    auto connect_jump = runtime->submit(
        std::make_unique<sshnative::TcpConnectOperation>(
            "127.0.0.1", static_cast<uint16_t>(jump_port), 5s));
    sshnative::RuntimeEvent event;
    assert(waitForEvent(*runtime, connect_jump, &event));
    assert(event.completion == sshnative::CompletionKind::kSucceeded);

    auto open_jump = runtime->submit(
        std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            "test", "secret-jump", "", "", "", /*store_as_jump=*/true));
    assert(open_jump && "runtime must accept open jump session");
    assert(waitForEvent(*runtime, open_jump, &event));
    if (event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "open jump failed: " << event.error.message << "\n";
        return 1;
    }

    auto probe_target = runtime->submit(
        std::make_unique<sshnative::OpenJumpTargetHandshakeOperation>(
            "127.0.0.1", static_cast<uint16_t>(target_port)));
    assert(probe_target && "runtime must accept jump target handshake");
    assert(waitForEvent(*runtime, probe_target, &event));
    if (event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "jump target handshake failed: " << event.error.message << "\n";
        return 1;
    }
    assert(event.payload.find("fingerprint=") == 0);
    assert(event.payload.find("keyType=") != std::string::npos);
    assert(event.payload.find("keyBase64=") != std::string::npos);

    auto open_target = runtime->submit(
        std::make_unique<sshnative::OpenJumpTargetSessionOperation>(
            "127.0.0.1", static_cast<uint16_t>(target_port),
            "test", "secret-target", "", "", ""));
    assert(open_target && "runtime must accept open jump target");
    assert(waitForEvent(*runtime, open_target, &event));
    if (event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "open jump target failed: " << event.error.message << "\n";
        return 1;
    }

    auto exec = runtime->submit(
        std::make_unique<sshnative::PersistentExecOperation>("test", 1024u));
    assert(waitForEvent(*runtime, exec, &event));
    if (event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "persistent exec through jump failed: "
                  << event.error.message << "\n";
        return 1;
    }
    if (event.payload.find("native-jump-poc-exec-ok") == std::string::npos) {
        std::cerr << "unexpected payload through jump: " << event.payload << "\n";
        return 1;
    }

    std::cout << "runtime-jump-ok\n";
    runtime->shutdown();
    return 0;
}

#include "ssh/ssh_connect_operation.h"
#include "ssh/ssh_direct_operation.h"
#include "ssh/ssh_runtime.h"

#include <chrono>
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <memory>

using namespace std::chrono_literals;

namespace {

bool waitForEvent(sshnative::SshNativeSession& runtime,
                  sshnative::SubmitResult result,
                  sshnative::RuntimeEvent* out) {
    const auto stop = sshnative::MonoClock::now() + 5s;
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
    if (argc != 2) return 64;
    const int port = std::atoi(argv[1]);

    auto runtime = sshnative::createSession();

    auto connect_result = runtime->submit(
        std::make_unique<sshnative::TcpConnectOperation>(
            "127.0.0.1", static_cast<uint16_t>(port), 5s));
    assert(connect_result && "runtime must accept tcp connect operation");
    sshnative::RuntimeEvent connect_event;
    assert(waitForEvent(*runtime, connect_result, &connect_event));
    assert(connect_event.completion == sshnative::CompletionKind::kSucceeded);
    assert(connect_event.payload == "connected");

    auto exec_result = runtime->submit(
        std::make_unique<sshnative::TcpPasswordExecOperation>(
            "127.0.0.1",
            static_cast<uint16_t>(port),
            "test",
            "secret",
            "true",
            5s,
            1024u,
            "",
            true));
    assert(exec_result && "runtime must accept pending transport exec");
    sshnative::RuntimeEvent exec_event;
    assert(waitForEvent(*runtime, exec_result, &exec_event));
    assert(exec_event.completion == sshnative::CompletionKind::kSucceeded);
    assert(exec_event.payload.find("exit=0\n") == 0);
    std::cout << "runtime-transport-handoff-ok\n";
    runtime->shutdown();
    return 0;
}

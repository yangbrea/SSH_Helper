#include "ssh/ssh_connect_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"
#include "ssh/ssh_shell_operation.h"

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
    if (argc != 2) return 64;
    const int port = std::atoi(argv[1]);
    auto runtime = sshnative::createSession();

    auto connect = runtime->submit(std::make_unique<sshnative::TcpConnectOperation>(
        "127.0.0.1", static_cast<uint16_t>(port), 5s));
    sshnative::RuntimeEvent e;
    assert(waitForEvent(*runtime, connect, &e) && e.completion == sshnative::CompletionKind::kSucceeded);

    auto open_session = runtime->submit(
        std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            "test", "secret", "", "", ""));
    assert(waitForEvent(*runtime, open_session, &e));
    assert(e.completion == sshnative::CompletionKind::kSucceeded);

    auto open = runtime->submit(
        std::make_unique<sshnative::OpenShellOperation>(
            80, 24, "xterm-256color", "true"));
    assert(open && "runtime must accept pty exec");
    assert(waitForEvent(*runtime, open, &e));
    if (e.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "pty exec open failed: " << e.error.message << "\n";
        return 1;
    }

    std::string output;
    bool found = false;
    for (int i = 0; i < 100; ++i) {
        auto read = runtime->submit(std::make_unique<sshnative::ShellReadOperation>(4096));
        assert(waitForEvent(*runtime, read, &e));
        if (e.completion != sshnative::CompletionKind::kSucceeded) {
            std::cerr << "pty exec read failed: " << e.error.message << "\n";
            return 1;
        }
        output += e.payload;
        if (output.find("native-exec-ok") != std::string::npos) {
            found = true;
            break;
        }
        if (e.payload.empty()) break;
    }
    if (!found) {
        std::cerr << "pty exec output missing: " << output << "\n";
        return 1;
    }

    auto close = runtime->submit(std::make_unique<sshnative::CloseShellOperation>());
    assert(waitForEvent(*runtime, close, &e));
    assert(e.completion == sshnative::CompletionKind::kSucceeded);
    std::cout << "runtime-pty-exec-ok\n";
    runtime->shutdown();
    return 0;
}

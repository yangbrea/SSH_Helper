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

    auto connect_result = runtime->submit(
        std::make_unique<sshnative::TcpConnectOperation>(
            "127.0.0.1", static_cast<uint16_t>(port), 5s));
    assert(connect_result);
    sshnative::RuntimeEvent connect_event;
    assert(waitForEvent(*runtime, connect_result, &connect_event));
    assert(connect_event.completion == sshnative::CompletionKind::kSucceeded);

    auto open_session = runtime->submit(
        std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            "test", "secret", "", "", ""));
    assert(open_session);
    sshnative::RuntimeEvent open_event;
    assert(waitForEvent(*runtime, open_session, &open_event));
    assert(open_event.completion == sshnative::CompletionKind::kSucceeded);

    auto open_shell = runtime->submit(
        std::make_unique<sshnative::OpenShellOperation>(80, 24));
    assert(open_shell && "runtime must accept open shell");
    sshnative::RuntimeEvent shell_event;
    assert(waitForEvent(*runtime, open_shell, &shell_event));
    if (shell_event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "open shell failed: " << shell_event.error.message << "\n";
        return 1;
    }

    auto write = runtime->submit(
        std::make_unique<sshnative::ShellWriteOperation>("shell-ok\n"));
    assert(write);
    sshnative::RuntimeEvent write_event;
    assert(waitForEvent(*runtime, write, &write_event));
    assert(write_event.completion == sshnative::CompletionKind::kSucceeded);

    std::string output;
    bool found = false;
    for (int i = 0; i < 100; ++i) {
        auto read = runtime->submit(
            std::make_unique<sshnative::ShellReadOperation>(4096));
        assert(read);
        sshnative::RuntimeEvent read_event;
        assert(waitForEvent(*runtime, read, &read_event));
        if (read_event.completion != sshnative::CompletionKind::kSucceeded) {
            std::cerr << "shell read failed: " << read_event.error.message << "\n";
            return 1;
        }
        output += read_event.payload;
        if (output.find("shell-ok") != std::string::npos) {
            found = true;
            break;
        }
        if (read_event.payload.empty()) break;
    }
    if (!found) {
        std::cerr << "shell output missing, got: " << output << "\n";
        return 1;
    }

    auto resize = runtime->submit(
        std::make_unique<sshnative::ShellResizeOperation>(120, 30));
    assert(resize);
    sshnative::RuntimeEvent resize_event;
    assert(waitForEvent(*runtime, resize, &resize_event));
    assert(resize_event.completion == sshnative::CompletionKind::kSucceeded);

    auto close_shell = runtime->submit(
        std::make_unique<sshnative::CloseShellOperation>());
    assert(close_shell);
    sshnative::RuntimeEvent close_event;
    assert(waitForEvent(*runtime, close_shell, &close_event));
    assert(close_event.completion == sshnative::CompletionKind::kSucceeded);

    std::cout << "runtime-shell-pty-ok\n";
    runtime->shutdown();
    return 0;
}

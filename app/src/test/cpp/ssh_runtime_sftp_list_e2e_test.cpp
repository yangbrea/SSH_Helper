#include "ssh/ssh_connect_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"
#include "ssh/ssh_sftp_operation.h"

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
    assert(waitForEvent(*runtime, connect, &e));
    assert(e.completion == sshnative::CompletionKind::kSucceeded);

    auto open_session = runtime->submit(
        std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            "test", "secret", "", "", ""));
    assert(waitForEvent(*runtime, open_session, &e));
    assert(e.completion == sshnative::CompletionKind::kSucceeded);

    auto list = runtime->submit(std::make_unique<sshnative::SftpListOperation>("."));
    assert(list && "runtime must accept sftp list");
    assert(waitForEvent(*runtime, list, &e));
    if (e.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "sftp list failed: " << e.error.message << "\n";
        return 1;
    }
    if (e.payload.find("scripts") == std::string::npos &&
        e.payload.find("app") == std::string::npos) {
        std::cerr << "sftp list output unexpected: " << e.payload << "\n";
        return 1;
    }
    const std::string first_line = e.payload.substr(0, e.payload.find('\n'));
    size_t tabs = 0;
    for (const char value : first_line) tabs += value == '\t' ? 1U : 0U;
    if (tabs != 6) {
        std::cerr << "sftp list metadata fields missing: " << first_line << "\n";
        return 1;
    }

    std::cout << "runtime-sftp-list-ok\n";
    runtime->shutdown();
    return 0;
}

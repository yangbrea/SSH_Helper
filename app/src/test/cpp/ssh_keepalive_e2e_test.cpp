#include "ssh/ssh_connect_operation.h"
#include "ssh/ssh_keepalive_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"

#include <chrono>
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <thread>

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
    const int port = std::atoi(argv[1]);
    const double die_after = std::atof(argv[2]);

    auto runtime = sshnative::createSession();
    auto connect_result = runtime->submit(
        std::make_unique<sshnative::TcpConnectOperation>(
            "127.0.0.1", static_cast<uint16_t>(port), 5s));
    sshnative::RuntimeEvent event;
    assert(waitForEvent(*runtime, connect_result, &event));
    if (event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "connect failed: " << event.error.message << "\n";
        return 1;
    }

    auto open_result = runtime->submit(
        std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            "test", "secret", "", "", ""));
    assert(waitForEvent(*runtime, open_result, &event));
    if (event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "open session failed: " << event.error.message << "\n";
        return 1;
    }

    if (die_after > 0) {
        std::this_thread::sleep_for(
            std::chrono::milliseconds(static_cast<long long>(die_after * 1000.0 + 700)));
    }

    auto keepalive_result = runtime->submit(
        std::make_unique<sshnative::KeepaliveOperation>(3s));
    assert(keepalive_result);
    assert(waitForEvent(*runtime, keepalive_result, &event));

    if (die_after > 0) {
        if (event.completion == sshnative::CompletionKind::kSucceeded) {
            std::cerr << "keepalive unexpectedly succeeded after remote close\n";
            return 1;
        }
        std::cout << "keepalive-disconnect-ok\n";
    } else {
        if (event.completion != sshnative::CompletionKind::kSucceeded) {
            std::cerr << "keepalive failed on live connection: "
                      << sshnative::errorDomainName(event.error.domain)
                      << "/" << event.error.code
                      << " " << event.error.message << "\n";
            return 1;
        }
        std::cout << "keepalive-live-ok\n";
    }

    runtime->shutdown();
    return 0;
}

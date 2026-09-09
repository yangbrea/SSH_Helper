#include "ssh/ssh_connect_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"

#include <chrono>
#include <cassert>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
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

std::string readFile(const char* path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) std::exit(65);
    std::ostringstream buffer;
    buffer << file.rdbuf();
    return buffer.str();
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 4) return 64;
    const int port = std::atoi(argv[1]);
    const std::string username = argv[2];
    const std::string private_key = readFile(argv[3]);

    auto runtime = sshnative::createSession();

    auto connect_result = runtime->submit(
        std::make_unique<sshnative::TcpConnectOperation>(
            "127.0.0.1", static_cast<uint16_t>(port), 5s));
    assert(connect_result);
    sshnative::RuntimeEvent connect_event;
    assert(waitForEvent(*runtime, connect_result, &connect_event));
    assert(connect_event.completion == sshnative::CompletionKind::kSucceeded);

    auto open_result = runtime->submit(
        std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            username, "", private_key, "", ""));
    assert(open_result && "runtime must accept open authenticated session");
    sshnative::RuntimeEvent open_event;
    assert(waitForEvent(*runtime, open_result, &open_event));
    if (open_event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "open session failed: domain="
                  << sshnative::errorDomainName(open_event.error.domain)
                  << " code=" << open_event.error.code
                  << " message=" << open_event.error.message << "\n";
        return 1;
    }
    assert(open_event.payload == "session=ok");

    auto exec_result = runtime->submit(
        std::make_unique<sshnative::PersistentExecOperation>("printf 'first\\n'", 1024u));
    assert(exec_result && "runtime must accept first persistent exec");
    sshnative::RuntimeEvent exec_event;
    assert(waitForEvent(*runtime, exec_result, &exec_event));
    if (exec_event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "first persistent exec failed: "
                  << exec_event.error.message << "\n";
        return 1;
    }
    assert(exec_event.payload.find("exit=0\n") == 0);
    assert(exec_event.payload.find("first") != std::string::npos);

    auto second_exec_result = runtime->submit(
        std::make_unique<sshnative::PersistentExecOperation>("printf 'second\\n'", 1024u));
    assert(second_exec_result && "runtime must accept second persistent exec");
    sshnative::RuntimeEvent second_event;
    assert(waitForEvent(*runtime, second_exec_result, &second_event));
    if (second_event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << "second persistent exec failed: "
                  << second_event.error.message << "\n";
        return 1;
    }
    assert(second_event.payload.find("exit=0\n") == 0);
    assert(second_event.payload.find("second") != std::string::npos);

    std::cout << "runtime-persistent-session-ok\n";
    runtime->shutdown();
    return 0;
}

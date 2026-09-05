#include "ssh/ssh_direct_operation.h"
#include "ssh/ssh_proxy_operation.h"
#include "ssh/ssh_runtime.h"
#include "ssh/ssh_socks5_operation.h"

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

std::unique_ptr<sshnative::Operation> makeProxyConnect(
    const std::string& proxy_type,
    int proxy_port,
    int ssh_port) {
    if (proxy_type == "http") {
        return std::make_unique<sshnative::HttpProxyConnectOperation>(
            "127.0.0.1", static_cast<uint16_t>(proxy_port),
            "127.0.0.1", static_cast<uint16_t>(ssh_port), "", "", 5s);
    }
    if (proxy_type == "socks5") {
        return std::make_unique<sshnative::Socks5ProxyConnectOperation>(
            "127.0.0.1", static_cast<uint16_t>(proxy_port),
            "127.0.0.1", static_cast<uint16_t>(ssh_port), "", "", 5s);
    }
    std::cerr << "unknown proxy type: " << proxy_type << "\n";
    std::exit(64);
}

std::string readFile(const char* path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) std::exit(65);
    std::ostringstream buffer;
    buffer << file.rdbuf();
    return buffer.str();
}

void runPasswordExec(sshnative::SshNativeSession& runtime,
                     const std::string& proxy_type,
                     int proxy_port,
                     int ssh_port) {
    auto connect_result = runtime.submit(makeProxyConnect(proxy_type, proxy_port, ssh_port));
    assert(connect_result && "runtime must accept proxy connect operation");
    sshnative::RuntimeEvent connect_event;
    assert(waitForEvent(runtime, connect_result, &connect_event));
    assert(connect_event.completion == sshnative::CompletionKind::kSucceeded);
    assert(connect_event.payload == "connected");

    auto exec_result = runtime.submit(
        std::make_unique<sshnative::TcpPasswordExecOperation>(
            "127.0.0.1",
            static_cast<uint16_t>(ssh_port),
            "test",
            "secret",
            "true",
            5s,
            1024u,
            "",
            true));
    assert(exec_result && "runtime must accept pending proxy exec");
    sshnative::RuntimeEvent exec_event;
    assert(waitForEvent(runtime, exec_result, &exec_event));
    assert(exec_event.completion == sshnative::CompletionKind::kSucceeded);
    assert(exec_event.payload.find("exit=0\n") == 0);
    assert(exec_event.payload.find("native-exec-ok") != std::string::npos);
}

void runPrivateKeyExec(sshnative::SshNativeSession& runtime,
                       const std::string& proxy_type,
                       int proxy_port,
                       int ssh_port,
                       const std::string& private_key) {
    auto connect_result = runtime.submit(makeProxyConnect(proxy_type, proxy_port, ssh_port));
    assert(connect_result && "runtime must accept proxy connect operation");
    sshnative::RuntimeEvent connect_event;
    assert(waitForEvent(runtime, connect_result, &connect_event));
    assert(connect_event.completion == sshnative::CompletionKind::kSucceeded);
    assert(connect_event.payload == "connected");

    auto exec_result = runtime.submit(
        std::make_unique<sshnative::TcpPrivateKeyExecOperation>(
            "127.0.0.1",
            static_cast<uint16_t>(ssh_port),
            "test",
            private_key,
            "secret",
            "true",
            5s,
            1024u,
            "",
            true));
    assert(exec_result && "runtime must accept pending proxy private key exec");
    sshnative::RuntimeEvent exec_event;
    assert(waitForEvent(runtime, exec_result, &exec_event));
    assert(exec_event.completion == sshnative::CompletionKind::kSucceeded);
    assert(exec_event.payload.find("exit=0\n") == 0);
    assert(exec_event.payload.find("native-exec-ok") != std::string::npos);
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 5) return 64;
    const int ssh_port = std::atoi(argv[1]);
    const std::string proxy_type = argv[2];
    const int proxy_port = std::atoi(argv[3]);
    const std::string private_key = readFile(argv[4]);

    auto runtime = sshnative::createSession();
    runPasswordExec(*runtime, proxy_type, proxy_port, ssh_port);
    runPrivateKeyExec(*runtime, proxy_type, proxy_port, ssh_port, private_key);
    std::cout << "runtime-proxy-" << proxy_type << "-exec-ok\n";
    runtime->shutdown();
    return 0;
}

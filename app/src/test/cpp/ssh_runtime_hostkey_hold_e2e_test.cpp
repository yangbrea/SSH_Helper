#include "ssh/ssh_error.h"
#include "ssh/ssh_handshake_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"

#include <cassert>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <sstream>
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

bool payloadHasFingerprint(const std::string& payload) {
    return payload.find("fingerprint=") != std::string::npos &&
        payload.find("keyType=") != std::string::npos &&
        payload.find("keyBase64=") != std::string::npos;
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    const int port = std::atoi(argv[1]);

    // Path A: accept host key, authenticate the held connection, then run exec
    // on the same active session.
    {
        auto runtime = sshnative::createSession();

        auto probe = submitAndWait(
            *runtime,
            std::make_unique<sshnative::TcpHandshakeOperation>(
                "127.0.0.1", static_cast<uint16_t>(port), 5s,
                /*hold_pending=*/true));
        expectSuccess(probe, "held host key probe");
        if (!payloadHasFingerprint(probe.payload)) {
            std::cerr << "probe payload missing host key fields\n";
            return 1;
        }

        auto auth = submitAndWait(
            *runtime,
            std::make_unique<sshnative::AuthenticatePendingSessionOperation>(
                "test", "secret", "", ""));
        expectSuccess(auth, "authenticate pending session");

        auto exec = submitAndWait(
            *runtime,
            std::make_unique<sshnative::PersistentExecOperation>(
                "printf 'held-session-ok\\n'", 1024u));
        expectSuccess(exec, "persistent exec after held auth");
        if (exec.payload.find("native-exec-ok") == std::string::npos) {
            std::cerr << "exec on held session missing output: " << exec.payload << "\n";
            return 1;
        }

        runtime->shutdown();
    }

    // Path B: reject/abort the held connection. A later exec should not be
    // possible because no active session was established.
    {
        auto runtime = sshnative::createSession();

        auto probe = submitAndWait(
            *runtime,
            std::make_unique<sshnative::TcpHandshakeOperation>(
                "127.0.0.1", static_cast<uint16_t>(port), 5s,
                /*hold_pending=*/true));
        expectSuccess(probe, "held host key probe for abort");

        auto abort = submitAndWait(
            *runtime,
            std::make_unique<sshnative::AbortPendingSessionOperation>());
        expectSuccess(abort, "abort pending session");

        auto exec = submitAndWait(
            *runtime,
            std::make_unique<sshnative::PersistentExecOperation>(
                "printf 'should-not-run\\n'", 1024u));
        if (exec.completion == sshnative::CompletionKind::kSucceeded) {
            std::cerr << "exec unexpectedly succeeded after aborting pending session\n";
            return 1;
        }

        runtime->shutdown();
    }

    std::cout << "runtime-hostkey-hold-ok\n";
    return 0;
}

#include "ssh/ssh_connect_operation.h"
#include "ssh/ssh_error.h"
#include "ssh/ssh_forward_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"
#include "ssh/ssh_socket.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <thread>

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

void expectSuccess(const sshnative::RuntimeEvent& event, const char* what) {
    if (event.completion != sshnative::CompletionKind::kSucceeded) {
        std::cerr << what << " failed: "
                  << sshnative::errorDomainName(event.error.domain)
                  << " code=" << event.error.code
                  << " message=" << event.error.message << "\n";
        std::exit(1);
    }
}

struct ForwardPayload {
    uint64_t id = 0;
    int port = 0;
};

ForwardPayload parseForwardPayload(const std::string& raw) {
    ForwardPayload result;
    std::istringstream input(raw);
    std::string line;
    while (std::getline(input, line)) {
        if (line.rfind("id=", 0) == 0) {
            result.id = std::strtoull(line.c_str() + 3, nullptr, 10);
        } else if (line.rfind("port=", 0) == 0) {
            result.port = std::atoi(line.c_str() + 5);
        }
    }
    if (result.id == 0) {
        std::cerr << "forward payload missing id: " << raw << "\n";
        std::exit(1);
    }
    return result;
}

class EchoServer {
public:
    EchoServer() {
        listener_ = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (listener_ < 0) std::exit(70);
        int reuse = 1;
        setsockopt(listener_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
        sockaddr_in address {};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        address.sin_port = 0;
        if (bind(listener_, reinterpret_cast<sockaddr*>(&address),
                 sizeof(address)) != 0 ||
            listen(listener_, 8) != 0) {
            std::exit(71);
        }
        sockaddr_in bound {};
        socklen_t bound_len = sizeof(bound);
        if (getsockname(listener_, reinterpret_cast<sockaddr*>(&bound),
                        &bound_len) != 0) {
            std::exit(72);
        }
        port_ = ntohs(bound.sin_port);
        thread_ = std::thread([this] { loop(); });
    }

    ~EchoServer() {
        stop();
    }

    int port() const { return port_; }

    void stop() {
        if (stopped_.exchange(true)) return;
        if (listener_ >= 0) {
            shutdown(listener_, SHUT_RDWR);
            close(listener_);
            listener_ = -1;
        }
        if (thread_.joinable()) thread_.join();
    }

private:
    void loop() {
        while (!stopped_.load()) {
            sockaddr_storage peer {};
            socklen_t peer_len = sizeof(peer);
            const int client = accept(
                listener_, reinterpret_cast<sockaddr*>(&peer), &peer_len);
            if (client < 0) break;
            char buffer[4096];
            while (true) {
                const ssize_t count = read(client, buffer, sizeof(buffer));
                if (count <= 0) break;
                size_t offset = 0;
                while (offset < static_cast<size_t>(count)) {
                    const ssize_t written = write(
                        client, buffer + offset, static_cast<size_t>(count) - offset);
                    if (written <= 0) break;
                    offset += static_cast<size_t>(written);
                }
            }
            close(client);
        }
        if (listener_ >= 0) {
            close(listener_);
            listener_ = -1;
        }
    }

    int listener_ = -1;
    int port_ = 0;
    std::atomic<bool> stopped_{false};
    std::thread thread_;
};

int findFreeTcpPort() {
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return 0;
    sockaddr_in address {};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = 0;
    if (bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        close(fd);
        return 0;
    }
    sockaddr_in bound {};
    socklen_t bound_len = sizeof(bound);
    if (getsockname(fd, reinterpret_cast<sockaddr*>(&bound), &bound_len) != 0) {
        close(fd);
        return 0;
    }
    const int port = ntohs(bound.sin_port);
    close(fd);
    return port;
}

int connectTcpLocal(int port) {

    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    sockaddr_in address {};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(static_cast<uint16_t>(port));
    if (connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

void echoRoundTrip(int fd, const std::string& payload) {
    if (write(fd, payload.data(), payload.size()) !=
        static_cast<ssize_t>(payload.size())) {
        std::exit(73);
    }
    std::string reply(payload.size(), '\0');
    size_t offset = 0;
    while (offset < reply.size()) {
        const ssize_t count = read(
            fd, &reply[offset], reply.size() - offset);
        if (count <= 0) {
            std::cerr << "echo reply truncated\n";
            std::exit(74);
        }
        offset += static_cast<size_t>(count);
    }
    if (reply != payload) {
        std::cerr << "echo mismatch\n";
        std::exit(75);
    }
}

sshnative::RuntimeEvent submitAndWait(
    sshnative::SshNativeSession& runtime,
    std::unique_ptr<sshnative::Operation> operation) {
    const auto submit = runtime.submit(std::move(operation));
    assert(submit && "runtime must accept operation");
    sshnative::RuntimeEvent event;
    if (!waitForEvent(runtime, submit, &event)) {
        std::cerr << "operation timed out\n";
        std::exit(76);
    }
    return event;
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    const int ssh_port = std::atoi(argv[1]);

    auto runtime = sshnative::createSession();

    auto connect_event = submitAndWait(
        *runtime,
        std::make_unique<sshnative::TcpConnectOperation>(
            "127.0.0.1", static_cast<uint16_t>(ssh_port), 5s));
    expectSuccess(connect_event, "tcp connect");

    auto open_event = submitAndWait(
        *runtime,
        std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            "test", "secret", "", "", ""));
    expectSuccess(open_event, "open session");

    EchoServer echo;

    // Local forwarding: client connects to the local listener; SSH server opens
    // direct-tcpip to the echo server.
    auto start_local_event = submitAndWait(
        *runtime,
        std::make_unique<sshnative::StartLocalForwardOperation>(
            "127.0.0.1", 0, "127.0.0.1",
            static_cast<uint16_t>(echo.port())));
    expectSuccess(start_local_event, "start local forward");
    const ForwardPayload local = parseForwardPayload(start_local_event.payload);
    if (local.port == 0) {
        std::cerr << "local forward did not allocate a port\n";
        return 1;
    }
    const int local_client = connectTcpLocal(local.port);
    if (local_client < 0) {
        std::cerr << "cannot connect to local forward listener\n";
        return 1;
    }
    echoRoundTrip(local_client, "local-forward-ok");
    close(local_client);

    auto close_local_event = submitAndWait(
        *runtime, std::make_unique<sshnative::CloseForwardOperation>(local.id));
    expectSuccess(close_local_event, "close local forward");

    // Remote forwarding: server-side listener forwards to the SSH client, which
    // connects back to the local echo server.
    auto start_remote_event = submitAndWait(
        *runtime,
        std::make_unique<sshnative::StartRemoteForwardOperation>(
            "127.0.0.1", findFreeTcpPort(), "127.0.0.1",
            static_cast<uint16_t>(echo.port())));
    expectSuccess(start_remote_event, "start remote forward");
    const ForwardPayload remote = parseForwardPayload(start_remote_event.payload);
    if (remote.port == 0) {
        std::cerr << "remote forward did not allocate a server port\n";
        return 1;
    }
    const int remote_client = connectTcpLocal(remote.port);
    if (remote_client < 0) {
        std::cerr << "cannot connect to remote forward listener on server\n";
        return 1;
    }
    echoRoundTrip(remote_client, "remote-forward-ok");
    close(remote_client);

    auto close_remote_event = submitAndWait(
        *runtime, std::make_unique<sshnative::CloseForwardOperation>(remote.id));
    expectSuccess(close_remote_event, "close remote forward");

    echo.stop();
    runtime->shutdown();
    std::cout << "runtime-forward-ok\n";
    return 0;
}

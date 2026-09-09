#include "ssh/ssh_socket.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cassert>
#include <chrono>
#include <stdexcept>
#include <cstdint>
#include <string>
#include <thread>

namespace {

int startLocalListener(uint16_t& port) {
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    assert(fd >= 0);
    sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    assert(bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    assert(listen(fd, 1) == 0);
    socklen_t len = sizeof(addr);
    assert(getsockname(fd, reinterpret_cast<sockaddr*>(&addr), &len) == 0);
    port = ntohs(addr.sin_port);
    return fd;
}


} // namespace

int main() {
    using namespace sshnative;

    // Successful non-blocking connect to a local listener.
    {
        uint16_t port = 0;
        const int listener = startLocalListener(port);
        std::thread acceptor([listener] {
            const int client = accept(listener, nullptr, nullptr);
            if (client >= 0) close(client);
        });
        const int fd = connectTcp("127.0.0.1", port, std::chrono::seconds(2));
        assert(fd >= 0);
        closeFd(fd);
        acceptor.join();
        close(listener);
    }

    // Connection refused is a stable error, not a timeout.
    {
        uint16_t port = 0;
        const int listener = startLocalListener(port);
        close(listener);
        bool threw = false;
        try {
            (void)connectTcp("127.0.0.1", port, std::chrono::milliseconds(500));
        } catch (const std::runtime_error&) {
            threw = true;
        }
        assert(threw);
    }

    // Unknown host surfaces DNS error.
    {
        bool threw = false;
        try {
            (void)connectTcp("nonexistent.invalid", 22, std::chrono::milliseconds(200));
        } catch (const std::runtime_error&) {
            threw = true;
        }
        assert(threw);
    }
}

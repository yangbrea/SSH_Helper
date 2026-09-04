#include "ssh/ssh_libssh2.h"
#include "ssh/ssh_socket.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cassert>
#include <chrono>
#include <cstdint>
#include <stdexcept>
#include <thread>

namespace {

int startListener(uint16_t& port) {
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

    {
        Libssh2Session session;
        session.setBlocking(true);
    }

    // A plain TCP peer that closes immediately must not crash handshake; it
    // should surface as a libssh2 error.
    {
        uint16_t port = 0;
        const int listener = startListener(port);
        std::thread closer([listener] {
            const int client = accept(listener, nullptr, nullptr);
            assert(client >= 0);
            close(client);
        });
        const int fd = connectTcp("127.0.0.1", port, std::chrono::seconds(2));
        Libssh2Session session;
        session.setBlocking(true);
        bool threw = false;
        try {
            session.handshake(fd);
        } catch (const std::runtime_error&) {
            threw = true;
        }
        assert(threw);
        close(fd);
        closer.join();
        close(listener);
    }
}

#include "ssh/ssh_libssh2.h"
#include "ssh/ssh_socket.h"

#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <iostream>

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    const int port = std::atoi(argv[1]);
    const int fd = sshnative::connectTcp(
        "127.0.0.1",
        static_cast<uint16_t>(port),
        std::chrono::seconds(5));
    sshnative::Libssh2Session session;
    session.setBlocking(true);
    session.handshake(fd);
    if (!session.passwordAuth("test", "secret")) {
        return 2;
    }
    std::cout << "auth-ok\n";
    return 0;
}

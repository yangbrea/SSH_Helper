#include "ssh/ssh_libssh2.h"
#include "ssh/ssh_socket.h"

#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

int main(int argc, char** argv) {
    if (argc != 3) return 64;
    const int port = std::atoi(argv[1]);
    std::ifstream key_file(argv[2], std::ios::binary);
    if (!key_file) return 65;
    std::ostringstream key_buffer;
    key_buffer << key_file.rdbuf();
    const std::string private_key = key_buffer.str();

    const int fd = sshnative::connectTcp(
        "127.0.0.1",
        static_cast<uint16_t>(port),
        std::chrono::seconds(5));
    sshnative::Libssh2Session session;
    session.setBlocking(true);
    session.handshake(fd);
    if (!session.publicKeyAuth("test", private_key, "")) {
        return 2;
    }
    const auto key = session.hostKey();
    if (key.empty()) return 3;

    std::string output;
    const int exit_code = session.execCommand("printf native-exec-ok", output);
    if (exit_code != 0 || output.find("native-exec-ok") == std::string::npos) {
        return 4;
    }
    std::cout << "auth-ok exec-ok\n";
    return 0;
}

#include "ssh/ssh_blocking_connection.h"

#include <cassert>
#include <cstdlib>
#include <iostream>
#include <string>

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    const int port = std::atoi(argv[1]);

    auto connection = sshnative::BlockingSshConnection::openDirect(
        "127.0.0.1",
        static_cast<uint16_t>(port));
    std::string output;
    const int exit_code = connection->execPassword(
        "test", "secret", "true", output);
    assert(exit_code == 0);
    assert(output.find("native-exec-ok") != std::string::npos);
    std::cout << "keyboard-interactive-ok\n";
    return 0;
}

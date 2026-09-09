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
    const auto host_key = connection->hostKeyDetails();
    assert(!host_key.type.empty());
    assert(host_key.fingerprint.rfind("SHA256:", 0) == 0);
    assert(!host_key.key_base64.empty());

    std::string output;
    const int exit_code = connection->execPassword(
        "test", "secret", "true", output);
    assert(exit_code == 0);
    assert(output.find("native-exec-ok") != std::string::npos);
    std::cout << "hostkey-gate-ok\n";
    return 0;
}

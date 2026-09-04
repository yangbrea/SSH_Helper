#include "ssh/ssh_hostkey.h"

#include <cassert>
#include <string>
#include <vector>

int main() {
    using namespace sshnative;

    const std::vector<uint8_t> blob = {'t', 'e', 's', 't', '-', 'k', 'e', 'y'};
    const std::string fingerprint = hostKeySha256Fingerprint(blob);
    assert(fingerprint.rfind("SHA256:", 0) == 0);
    assert(fingerprint.size() > 7);

    assert(hostKeyTypeName(1).find("rsa") != std::string::npos);
    assert(hostKeyTypeName(2) == "ssh-dss");
    assert(hostKeyTypeName(4) == "ssh-ed25519");
}

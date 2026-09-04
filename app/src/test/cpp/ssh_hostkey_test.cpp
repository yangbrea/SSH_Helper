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

    const std::string base64 = hostKeyBase64(blob);
    assert(base64.size() > 0);
    assert(base64.find('=') == std::string::npos || base64.size() % 4 == 0);

    assert(hostKeyTypeName(1) == "ssh-rsa");
    assert(hostKeyTypeName(2) == "ssh-dss");
    assert(hostKeyTypeName(4) == "ecdsa-sha2-nistp384");
    assert(hostKeyTypeName(5) == "ecdsa-sha2-nistp521");
    assert(hostKeyTypeName(6) == "ssh-ed25519");
}

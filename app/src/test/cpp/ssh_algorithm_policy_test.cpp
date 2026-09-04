#include "ssh/ssh_algorithm_policy.h"

#include <cassert>

int main() {
    using namespace sshnative;

    assert(isAllowedKex("curve25519-sha256"));
    assert(isAllowedKex("ecdh-sha2-nistp256"));
    assert(isAllowedKex("diffie-hellman-group16-sha512"));
    assert(!isAllowedKex("diffie-hellman-group1-sha1"));
    assert(!isAllowedKex("diffie-hellman-group14-sha1"));
    assert(!isAllowedKex("diffie-hellman-group-exchange-sha1"));

    assert(isAllowedHostKey("ssh-ed25519"));
    assert(isAllowedHostKey("ecdsa-sha2-nistp256"));
    assert(isAllowedHostKey("rsa-sha2-256"));
    assert(isAllowedHostKey("rsa-sha2-512"));
    assert(!isAllowedHostKey("ssh-rsa"));
    assert(!isAllowedHostKey("ssh-dss"));

    assert(isAllowedCipher("chacha20-poly1305@openssh.com"));
    assert(isAllowedCipher("aes128-gcm@openssh.com"));
    assert(isAllowedCipher("aes256-ctr"));
    assert(!isAllowedCipher("aes128-cbc"));
    assert(!isAllowedCipher("3des-cbc"));
    assert(!isAllowedCipher("blowfish-cbc"));
    assert(!isAllowedCipher("rc4"));

    assert(isAllowedMac("hmac-sha2-256"));
    assert(isAllowedMac("hmac-sha2-512-etm@openssh.com"));
    assert(!isAllowedMac("hmac-sha1"));
    assert(!isAllowedMac("hmac-md5"));
}

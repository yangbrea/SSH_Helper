#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace sshnative {

// Returns the OpenSSH-style SHA256 fingerprint for a raw host-key blob.
std::string hostKeySha256Fingerprint(const std::vector<uint8_t>& key_blob);

// Returns the standard padded Base64 encoding of a raw host-key blob.
std::string hostKeyBase64(const std::vector<uint8_t>& key_blob);

// Maps libssh2 host key type constants to stable string names.
std::string hostKeyTypeName(int type);

} // namespace sshnative

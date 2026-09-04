#include "ssh_hostkey.h"

#include <openssl/evp.h>
#include <openssl/sha.h>

#include <array>
#include <cstdint>
#include <vector>

namespace sshnative {

namespace {

std::string base64EncodeNoPadding(const uint8_t* data, size_t size) {
    static constexpr char kTable[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string output;
    output.reserve(((size + 2) / 3) * 4);
    size_t i = 0;
    while (i + 2 < size) {
        const uint32_t value = (static_cast<uint32_t>(data[i]) << 16) |
            (static_cast<uint32_t>(data[i + 1]) << 8) |
            static_cast<uint32_t>(data[i + 2]);
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
        output.push_back(kTable[(value >> 6) & 0x3F]);
        output.push_back(kTable[value & 0x3F]);
        i += 3;
    }
    const size_t remaining = size - i;
    if (remaining == 1) {
        const uint32_t value = static_cast<uint32_t>(data[i]) << 16;
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
    } else if (remaining == 2) {
        const uint32_t value = (static_cast<uint32_t>(data[i]) << 16) |
            (static_cast<uint32_t>(data[i + 1]) << 8);
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
        output.push_back(kTable[(value >> 6) & 0x3F]);
    }
    return output;
}

} // namespace

std::string hostKeySha256Fingerprint(const std::vector<uint8_t>& key_blob) {
    std::array<uint8_t, SHA256_DIGEST_LENGTH> digest{};
    SHA256(key_blob.data(), key_blob.size(), digest.data());
    return "SHA256:" + base64EncodeNoPadding(digest.data(), digest.size());
}

std::string hostKeyBase64(const std::vector<uint8_t>& key_blob) {
    if (key_blob.empty()) return std::string();
    std::string output(((key_blob.size() + 2) / 3) * 4, '\0');
    const int encoded = EVP_EncodeBlock(
        reinterpret_cast<unsigned char*>(&output[0]),
        key_blob.data(),
        static_cast<int>(key_blob.size()));
    if (encoded <= 0) return std::string();
    output.resize(static_cast<size_t>(encoded));
    return output;
}

std::string hostKeyTypeName(int type) {
    switch (type) {
        case 0:
            return "unknown";
        case 1:
            return "ssh-rsa";
        case 2:
            return "ssh-dss";
        case 3:
            return "ecdsa-sha2-nistp256";
        case 4:
            return "ecdsa-sha2-nistp384";
        case 5:
            return "ecdsa-sha2-nistp521";
        case 6:
            return "ssh-ed25519";
        default:
            return "unknown";
    }
}

} // namespace sshnative

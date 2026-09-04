#include "ssh_algorithm_policy.h"

#include <algorithm>
#include <cctype>
#include <string>

namespace sshnative {

namespace {

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool contains(const std::string& value, const char* needle) {
    return value.find(needle) != std::string::npos;
}

} // namespace

bool isAllowedKex(const std::string& name) {
    const std::string value = lower(name);
    if (value == "diffie-hellman-group1-sha1" ||
        value == "diffie-hellman-group14-sha1" ||
        value == "diffie-hellman-group-exchange-sha1") {
        return false;
    }
    return !value.empty();
}

bool isAllowedHostKey(const std::string& name) {
    const std::string value = lower(name);
    if (value == "ssh-rsa" || value == "ssh-dss") {
        return false;
    }
    return !value.empty();
}

bool isAllowedCipher(const std::string& name) {
    const std::string value = lower(name);
    if (contains(value, "cbc") || contains(value, "3des") ||
        contains(value, "blowfish") || contains(value, "cast") ||
        contains(value, "rc4") || contains(value, "-des-") ||
        value == "des") {
        return false;
    }
    return !value.empty();
}

bool isAllowedMac(const std::string& name) {
    const std::string value = lower(name);
    if (contains(value, "sha1") || contains(value, "md5")) {
        return false;
    }
    return !value.empty();
}

} // namespace sshnative

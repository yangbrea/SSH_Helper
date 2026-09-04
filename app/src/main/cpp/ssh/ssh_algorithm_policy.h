#pragma once

#include <string>

namespace sshnative {

// Algorithm policy for the native SSH backend. Weak algorithms must never be
// re-enabled as a compatibility fallback.
bool isAllowedKex(const std::string& name);
bool isAllowedHostKey(const std::string& name);
bool isAllowedCipher(const std::string& name);
bool isAllowedMac(const std::string& name);

} // namespace sshnative

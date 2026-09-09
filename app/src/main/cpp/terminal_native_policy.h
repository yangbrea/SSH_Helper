#pragma once

#include <cstddef>
#include <cstdint>

enum class NativePasteResult : int32_t {
    Written = 0,
    Empty = 1,
    Rejected = 2,
    Error = 3,
};

constexpr NativePasteResult pasteResult(bool has_input, bool native_success, bool written) {
    if (!has_input) return NativePasteResult::Empty;
    if (!native_success) return NativePasteResult::Error;
    return written ? NativePasteResult::Written : NativePasteResult::Rejected;
}

constexpr bool snapshotFits(size_t required, size_t capacity) {
    return required <= capacity;
}

class GenerationCounter {
public:
    constexpr uint64_t current() const { return value_; }
    constexpr uint64_t advance() { return ++value_; }
    constexpr bool isCurrent(uint64_t candidate) const { return value_ == candidate; }

private:
    uint64_t value_ = 0;
};

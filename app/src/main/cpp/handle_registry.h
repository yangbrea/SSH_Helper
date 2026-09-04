#pragma once

#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>

template <typename T>
class HandleRegistry {
public:
    int64_t insert(std::shared_ptr<T> value) {
        std::lock_guard<std::mutex> lock(mutex_);
        int64_t id;
        do {
            id = next_id_;
            next_id_ = next_id_ == std::numeric_limits<int64_t>::max()
                ? 1
                : next_id_ + 1;
        } while (id == 0 || values_.find(id) != values_.end());
        values_.emplace(id, std::move(value));
        return id;
    }

    std::shared_ptr<T> get(int64_t id) const {
        if (id == 0) return {};
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = values_.find(id);
        return found == values_.end() ? std::shared_ptr<T>{} : found->second;
    }

    std::shared_ptr<T> remove(int64_t id) {
        if (id == 0) return {};
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = values_.find(id);
        if (found == values_.end()) return {};
        auto value = std::move(found->second);
        values_.erase(found);
        return value;
    }

private:
    mutable std::mutex mutex_;
    std::unordered_map<int64_t, std::shared_ptr<T>> values_;
    int64_t next_id_ = 1;
};

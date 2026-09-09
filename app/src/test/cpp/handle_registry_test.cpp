#include "handle_registry.h"
#include "terminal_native_policy.h"

#include <atomic>
#include <cassert>
#include <memory>
#include <thread>
#include <vector>

int main() {
    assert(pasteResult(false, true, true) == NativePasteResult::Empty);
    assert(pasteResult(true, false, false) == NativePasteResult::Error);
    assert(pasteResult(true, true, false) == NativePasteResult::Rejected);
    assert(pasteResult(true, true, true) == NativePasteResult::Written);

    assert(snapshotFits(0, 0));
    assert(snapshotFits(4096, 4096));
    assert(!snapshotFits(4097, 4096));

    GenerationCounter search_generation;
    const auto stale = search_generation.current();
    const auto current = search_generation.advance();
    assert(!search_generation.isCurrent(stale));
    assert(search_generation.isCurrent(current));

    HandleRegistry<int> registry;
    const auto first = registry.insert(std::make_shared<int>(7));
    assert(first != 0);
    assert(*registry.get(first) == 7);
    assert(*registry.remove(first) == 7);
    assert(registry.remove(first) == nullptr);
    assert(registry.get(first) == nullptr);

    std::atomic<int> observed{0};
    std::vector<std::thread> threads;
    for (int i = 0; i < 8; ++i) {
        threads.emplace_back([&] {
            for (int j = 0; j < 1'000; ++j) {
                const auto id = registry.insert(std::make_shared<int>(j));
                if (registry.get(id) != nullptr) observed.fetch_add(1);
                assert(registry.remove(id) != nullptr);
                assert(registry.remove(id) == nullptr);
            }
        });
    }
    for (auto& thread : threads) thread.join();
    assert(observed == 8'000);
}

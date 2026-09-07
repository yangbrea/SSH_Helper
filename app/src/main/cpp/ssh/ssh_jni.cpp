// JNI boundary for the native libssh2/OpenSSL SSH runtime.
//
// The handle exposed to Kotlin is an opaque registry id, never a native
// pointer. Close is idempotent: nativeClose(0), repeated close and close of an
// already-removed handle are safe no-ops. All C++ exceptions are caught at the
// JNI boundary and converted into Java exceptions.

#include <jni.h>

#include <libssh2.h>
#include <openssl/opensslv.h>

#include <cstdint>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <new>
#include <string>
#include <vector>

#include "../handle_registry.h"
#include "ssh_connect_operation.h"
#include "ssh_direct_operation.h"
#include "ssh_proxy_operation.h"
#include "ssh_socks5_operation.h"
#include "ssh_error.h"
#include "ssh_handshake_operation.h"
#include "ssh_jump_operation.h"
#include "ssh_libssh2.h"
#include "ssh_operations.h"
#include "ssh_persistent_session.h"
#include "ssh_runtime.h"
#include "ssh_sftp_operation.h"
#include "ssh_shell_operation.h"
#include "ssh_socket.h"

namespace {

HandleRegistry<sshnative::SshNativeSession> gSshRegistry;

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

void throwOutOfMemory(JNIEnv* env) {
    jclass exceptionClass = env->FindClass("java/lang/OutOfMemoryError");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, "SshNativeSession allocation failed");
    }
}

std::string currentAbi() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__x86_64__)
    return "x86_64";
#else
    return "unknown";
#endif
}

std::string versionString() {
    std::string version = "libssh2 ";
    version += LIBSSH2_VERSION;
    version += " + OpenSSL ";
    version += OPENSSL_VERSION_TEXT;
    return version;
}

std::string capabilitiesString() {
    std::string capabilities = "libssh2=";
    capabilities += LIBSSH2_VERSION;
    capabilities += ";openssl=";
    capabilities += OPENSSL_VERSION_TEXT;
    capabilities += ";abi=";
    capabilities += currentAbi();
    capabilities += ";crypto_backend=openssl";
    capabilities += ";legacy_algorithms=false";
    return capabilities;
}

jobject newRuntimeEvent(JNIEnv* env, const sshnative::RuntimeEvent& event) {
    jclass event_class = env->FindClass(
        "com/yang136/sshhelper/ssh/native/NativeSshEvent");
    if (event_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(
        event_class, "<init>",
        "(IJIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V");
    if (constructor == nullptr) return nullptr;

    jstring domain = env->NewStringUTF(sshnative::errorDomainName(event.error.domain));
    jstring code = env->NewStringUTF(event.error.code.c_str());
    jstring message = env->NewStringUTF(event.error.message.c_str());
    jbyteArray payload = env->NewByteArray(static_cast<jsize>(event.payload.size()));
    if (domain == nullptr || code == nullptr || message == nullptr || payload == nullptr) {
        return nullptr;
    }
    if (!event.payload.empty()) {
        env->SetByteArrayRegion(payload, 0, static_cast<jsize>(event.payload.size()),
                                reinterpret_cast<const jbyte*>(event.payload.data()));
    }
    return env->NewObject(
        event_class, constructor,
        static_cast<jint>(event.kind), static_cast<jlong>(event.request_id),
        static_cast<jint>(event.completion), static_cast<jint>(event.session_state),
        domain, code, message, payload);
}

std::string awaitRuntimeCompletion(
    JNIEnv* env,
    const std::shared_ptr<sshnative::SshNativeSession>& session,
    sshnative::SubmitResult submit,
    bool map_timeout_to_exit_124 = false,
    bool include_error_code = false) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    while (std::chrono::steady_clock::now() < deadline) {
        sshnative::RuntimeEvent event;
        if (!session->waitEvent(&event, std::chrono::milliseconds(100))) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != submit.request_id) {
            continue;
        }
        if (event.completion == sshnative::CompletionKind::kSucceeded) {
            return std::move(event.payload);
        }
        if (map_timeout_to_exit_124 &&
            event.error.domain == sshnative::ErrorDomain::kTimeout) {
            return "exit=124\n";
        }
        std::string message = event.error.message.empty()
            ? "native runtime operation failed"
            : event.error.message;
        if (include_error_code && !event.error.code.empty()) {
            message = event.error.code + ":" + message;
        }
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, message.c_str());
        }
        return {};
    }
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, "native runtime operation timed out");
    }
    return {};
}

// Like awaitRuntimeCompletion, but a deadline timeout returns false with no
// Java exception. Used by shell reads so a blocked reader can be polled and
// stopped without needing to cancel the native read request from Kotlin.
bool awaitRuntimeCompletionWithDeadline(
    JNIEnv* env,
    const std::shared_ptr<sshnative::SshNativeSession>& session,
    sshnative::SubmitResult submit,
    std::chrono::milliseconds timeout,
    std::string* out) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (std::chrono::steady_clock::now() < deadline) {
        sshnative::RuntimeEvent event;
        if (!session->waitEvent(&event, std::chrono::milliseconds(50))) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != submit.request_id) {
            continue;
        }
        if (event.completion == sshnative::CompletionKind::kSucceeded) {
            *out = std::move(event.payload);
            return true;
        }
        if (event.error.domain == sshnative::ErrorDomain::kTimeout) {
            return false;
        }
        const std::string message = event.error.message.empty()
            ? "native runtime operation failed"
            : event.error.message;
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, message.c_str());
        }
        return false;
    }
    return false;
}

} // namespace


namespace {

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

// JNI's GetStringUTFChars/NewStringUTF use modified UTF-8 (CESU-8 for
// supplementary characters), while SFTP paths are ordinary UTF-8 byte
// strings. Keep the existing modified-UTF helpers for legacy JNI calls, but
// use these conversions at the SFTP boundary so names containing characters
// outside the BMP (for example emoji) round-trip correctly.
std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return std::string();

    std::string result;
    result.reserve(static_cast<size_t>(length));
    for (jsize index = 0; index < length; ++index) {
        uint32_t code_point = chars[index];
        if (code_point >= 0xD800 && code_point <= 0xDBFF && index + 1 < length) {
            const uint32_t low = chars[index + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                code_point = 0x10000 + ((code_point - 0xD800) << 10) +
                    (low - 0xDC00);
                ++index;
            } else {
                code_point = 0xFFFD;
            }
        } else if (code_point >= 0xD800 && code_point <= 0xDFFF) {
            code_point = 0xFFFD;
        }

        if (code_point <= 0x7F) {
            result.push_back(static_cast<char>(code_point));
        } else if (code_point <= 0x7FF) {
            result.push_back(static_cast<char>(0xC0 | (code_point >> 6)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        } else if (code_point <= 0xFFFF) {
            result.push_back(static_cast<char>(0xE0 | (code_point >> 12)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        } else {
            result.push_back(static_cast<char>(0xF0 | (code_point >> 18)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring utf8ToJString(JNIEnv* env, const std::string& value) {
    std::vector<jchar> result;
    result.reserve(value.size());
    size_t index = 0;
    while (index < value.size()) {
        const uint8_t first = static_cast<uint8_t>(value[index]);
        uint32_t code_point = 0xFFFD;
        size_t width = 1;
        uint32_t minimum = 0;
        if (first <= 0x7F) {
            code_point = first;
        } else if ((first & 0xE0) == 0xC0) {
            code_point = first & 0x1F;
            width = 2;
            minimum = 0x80;
        } else if ((first & 0xF0) == 0xE0) {
            code_point = first & 0x0F;
            width = 3;
            minimum = 0x800;
        } else if ((first & 0xF8) == 0xF0) {
            code_point = first & 0x07;
            width = 4;
            minimum = 0x10000;
        }

        bool valid = width == 1 ? first <= 0x7F : index + width <= value.size();
        for (size_t offset = 1; valid && offset < width; ++offset) {
            const uint8_t continuation =
                static_cast<uint8_t>(value[index + offset]);
            if ((continuation & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            code_point = (code_point << 6) | (continuation & 0x3F);
        }
        valid = valid && code_point >= minimum && code_point <= 0x10FFFF &&
            !(code_point >= 0xD800 && code_point <= 0xDFFF);
        if (!valid) {
            code_point = 0xFFFD;
            width = 1;
        }
        index += width;

        if (code_point <= 0xFFFF) {
            result.push_back(static_cast<jchar>(code_point));
        } else {
            code_point -= 0x10000;
            result.push_back(static_cast<jchar>(0xD800 | (code_point >> 10)));
            result.push_back(static_cast<jchar>(0xDC00 | (code_point & 0x3FF)));
        }
    }
    static constexpr jchar kEmpty = 0;
    return env->NewString(result.empty() ? &kEmpty : result.data(),
                          static_cast<jsize>(result.size()));
}

std::string jbyteArrayToString(JNIEnv* env, jbyteArray value) {
    if (value == nullptr) return std::string();
    const jsize length = env->GetArrayLength(value);
    if (length <= 0) return std::string();
    std::string result;
    result.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(&result[0]));
    return result;
}

jbyteArray stringToJByteArray(JNIEnv* env, const std::string& value) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte*>(value.data()));
    return result;
}

} // namespace
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    return JNI_VERSION_1_6;
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunHttpProxyConnect(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jproxy_host,
    jint jproxy_port,
    jstring jtarget_host,
    jint jtarget_port,
    jstring jusername,
    jstring jpassword,
    jlong timeout_millis) {
    try {
        const std::string proxy_host = jstringToString(env, jproxy_host);
        const std::string target_host = jstringToString(env, jtarget_host);
        const std::string username = jstringToString(env, jusername);
        const std::string password = jstringToString(env, jpassword);
        if (proxy_host.empty() || target_host.empty()) {
            throw std::invalid_argument("proxy/target host must not be empty");
        }
        if (jproxy_port <= 0 || jproxy_port > 65535 || jtarget_port <= 0 ||
            jtarget_port > 65535 || timeout_millis <= 0) {
            throw std::invalid_argument("invalid proxy/target port/timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::HttpProxyConnectOperation>(
            proxy_host, static_cast<uint16_t>(jproxy_port),
            target_host, static_cast<uint16_t>(jtarget_port),
            username, password, std::chrono::milliseconds(timeout_millis));
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit http proxy connect");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunHttpProxyConnect failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunSocks5ProxyConnect(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jproxy_host,
    jint jproxy_port,
    jstring jtarget_host,
    jint jtarget_port,
    jstring jusername,
    jstring jpassword,
    jlong timeout_millis) {
    try {
        const std::string proxy_host = jstringToString(env, jproxy_host);
        const std::string target_host = jstringToString(env, jtarget_host);
        const std::string username = jstringToString(env, jusername);
        const std::string password = jstringToString(env, jpassword);
        if (proxy_host.empty() || target_host.empty()) {
            throw std::invalid_argument("proxy/target host must not be empty");
        }
        if (jproxy_port <= 0 || jproxy_port > 65535 || jtarget_port <= 0 ||
            jtarget_port > 65535 || timeout_millis <= 0) {
            throw std::invalid_argument("invalid proxy/target port/timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::Socks5ProxyConnectOperation>(
            proxy_host, static_cast<uint16_t>(jproxy_port),
            target_host, static_cast<uint16_t>(jtarget_port),
            username, password, std::chrono::milliseconds(timeout_millis));
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit socks5 proxy connect");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunSocks5ProxyConnect failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunTcpHandshake(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jhost,
    jint jport,
    jlong timeout_millis) {
    try {
        const std::string host = jstringToString(env, jhost);
        if (host.empty()) throw std::invalid_argument("host must not be empty");
        if (jport <= 0 || jport > 65535 || timeout_millis <= 0) {
            throw std::invalid_argument("invalid port/timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::TcpHandshakeOperation>(
            host, static_cast<uint16_t>(jport),
            std::chrono::milliseconds(timeout_millis));
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit tcp handshake");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunTcpHandshake failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunPendingTcpHandshake(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong timeout_millis) {
    try {
        if (timeout_millis <= 0) {
            throw std::invalid_argument("invalid timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::Libssh2HandshakeOperation>();
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit pending tcp handshake");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunPendingTcpHandshake failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunPendingDirectPasswordExec(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jhost,
    jint jport,
    jstring jusername,
    jstring jpassword,
    jstring jcommand,
    jstring jexpected_fingerprint,
    jlong connect_timeout_millis,
    jlong exec_timeout_millis,
    jint max_output_bytes) {
    try {
        const std::string host = jstringToString(env, jhost);
        const std::string username = jstringToString(env, jusername);
        const std::string password = jstringToString(env, jpassword);
        const std::string command = jstringToString(env, jcommand);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (host.empty() || username.empty() || password.empty() || command.empty()) {
            throw std::invalid_argument("host/username/password/command must not be empty");
        }
        if (jport <= 0 || jport > 65535 || connect_timeout_millis <= 0 ||
            exec_timeout_millis <= 0 || max_output_bytes <= 0) {
            throw std::invalid_argument("invalid port/timeout/max output");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::TcpPasswordExecOperation>(
            host, static_cast<uint16_t>(jport), username, password, command,
            std::chrono::milliseconds(connect_timeout_millis),
            static_cast<size_t>(max_output_bytes), expected_fingerprint,
            /*take_pending_transport=*/true);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(exec_timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit pending direct password exec");
        }
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, /*map_timeout_to_exit_124=*/true);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunPendingDirectPasswordExec failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunPendingDirectPrivateKeyExec(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jhost,
    jint jport,
    jstring jusername,
    jbyteArray jprivateKey,
    jstring jpassphrase,
    jstring jcommand,
    jstring jexpected_fingerprint,
    jlong connect_timeout_millis,
    jlong exec_timeout_millis,
    jint max_output_bytes) {
    try {
        const std::string host = jstringToString(env, jhost);
        const std::string username = jstringToString(env, jusername);
        const std::string private_key = jbyteArrayToString(env, jprivateKey);
        const std::string passphrase = jstringToString(env, jpassphrase);
        const std::string command = jstringToString(env, jcommand);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (host.empty() || username.empty() || private_key.empty() || command.empty()) {
            throw std::invalid_argument("host/username/private key/command must not be empty");
        }
        if (jport <= 0 || jport > 65535 || connect_timeout_millis <= 0 ||
            exec_timeout_millis <= 0 || max_output_bytes <= 0) {
            throw std::invalid_argument("invalid port/timeout/max output");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::TcpPrivateKeyExecOperation>(
            host, static_cast<uint16_t>(jport), username, private_key, passphrase,
            command, std::chrono::milliseconds(connect_timeout_millis),
            static_cast<size_t>(max_output_bytes), expected_fingerprint,
            /*take_pending_transport=*/true);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(exec_timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit pending direct private key exec");
        }
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, /*map_timeout_to_exit_124=*/true);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunPendingDirectPrivateKeyExec failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunTcpConnect(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jhost,
    jint jport,
    jlong timeout_millis) {
    try {
        const std::string host = jstringToString(env, jhost);
        if (host.empty()) throw std::invalid_argument("host must not be empty");
        if (jport <= 0 || jport > 65535 || timeout_millis <= 0) {
            throw std::invalid_argument("invalid port/timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::TcpConnectOperation>(
            host, static_cast<uint16_t>(jport),
            std::chrono::milliseconds(timeout_millis));
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit tcp connect");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunTcpConnect failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunOpenSession(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jusername,
    jstring jpassword,
    jstring jexpected_fingerprint,
    jlong timeout_millis) {
    try {
        const std::string username = jstringToString(env, jusername);
        const std::string password = jstringToString(env, jpassword);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (username.empty() || password.empty()) {
            throw std::invalid_argument("username/password must not be empty");
        }
        if (timeout_millis <= 0) throw std::invalid_argument("invalid timeout");
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            username, password, "", "", expected_fingerprint);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit open session");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunOpenSession failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunOpenSessionWithPrivateKey(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jusername,
    jbyteArray jprivateKey,
    jstring jpassphrase,
    jstring jexpected_fingerprint,
    jlong timeout_millis) {
    try {
        const std::string username = jstringToString(env, jusername);
        const std::string private_key = jbyteArrayToString(env, jprivateKey);
        const std::string passphrase = jstringToString(env, jpassphrase);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (username.empty() || private_key.empty()) {
            throw std::invalid_argument("username/private key must not be empty");
        }
        if (timeout_millis <= 0) throw std::invalid_argument("invalid timeout");
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            username, "", private_key, passphrase, expected_fingerprint);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit open session with private key");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunOpenSessionWithPrivateKey failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunOpenJumpSession(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jusername,
    jstring jpassword,
    jstring jexpected_fingerprint,
    jlong timeout_millis) {
    try {
        const std::string username = jstringToString(env, jusername);
        const std::string password = jstringToString(env, jpassword);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (username.empty() || password.empty()) {
            throw std::invalid_argument("username/password must not be empty");
        }
        if (timeout_millis <= 0) throw std::invalid_argument("invalid timeout");
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            username, password, "", "", expected_fingerprint, /*store_as_jump=*/true);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit open jump session");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunOpenJumpSession failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunOpenJumpSessionWithPrivateKey(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jusername,
    jbyteArray jprivateKey,
    jstring jpassphrase,
    jstring jexpected_fingerprint,
    jlong timeout_millis) {
    try {
        const std::string username = jstringToString(env, jusername);
        const std::string private_key = jbyteArrayToString(env, jprivateKey);
        const std::string passphrase = jstringToString(env, jpassphrase);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (username.empty() || private_key.empty()) {
            throw std::invalid_argument("username/private key must not be empty");
        }
        if (timeout_millis <= 0) throw std::invalid_argument("invalid timeout");
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
            username, "", private_key, passphrase, expected_fingerprint, /*store_as_jump=*/true);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit open jump session with private key");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunOpenJumpSessionWithPrivateKey failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunOpenJumpTargetHandshake(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jtarget_host,
    jint jtarget_port,
    jlong timeout_millis) {
    try {
        const std::string target_host = jstringToString(env, jtarget_host);
        if (target_host.empty()) {
            throw std::invalid_argument("target host must not be empty");
        }
        if (jtarget_port <= 0 || jtarget_port > 65535 || timeout_millis <= 0) {
            throw std::invalid_argument("invalid target port/timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenJumpTargetHandshakeOperation>(
            target_host, static_cast<uint16_t>(jtarget_port));
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit open jump target handshake");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunOpenJumpTargetHandshake failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunOpenJumpTargetSession(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jtarget_host,
    jint jtarget_port,
    jstring jusername,
    jstring jpassword,
    jbyteArray jprivateKey,
    jstring jpassphrase,
    jstring jexpected_fingerprint,
    jlong timeout_millis) {
    try {
        const std::string target_host = jstringToString(env, jtarget_host);
        const std::string username = jstringToString(env, jusername);
        const std::string password = jstringToString(env, jpassword);
        const std::string private_key = jbyteArrayToString(env, jprivateKey);
        const std::string passphrase = jstringToString(env, jpassphrase);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (target_host.empty() || username.empty() ||
            (password.empty() && private_key.empty())) {
            throw std::invalid_argument("target host/username/credential must not be empty");
        }
        if (jtarget_port <= 0 || jtarget_port > 65535 || timeout_millis <= 0) {
            throw std::invalid_argument("invalid target port/timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenJumpTargetSessionOperation>(
            target_host, static_cast<uint16_t>(jtarget_port),
            username, password, private_key, passphrase, expected_fingerprint);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit open jump target session");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunOpenJumpTargetSession failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunPersistentExec(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jcommand,
    jint max_output_bytes,
    jlong timeout_millis) {
    try {
        const std::string command = jstringToString(env, jcommand);
        if (command.empty()) throw std::invalid_argument("command must not be empty");
        if (max_output_bytes <= 0 || timeout_millis <= 0) {
            throw std::invalid_argument("invalid max output/timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::PersistentExecOperation>(
            command, static_cast<size_t>(max_output_bytes));
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit persistent exec");
        }
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, /*map_timeout_to_exit_124=*/true);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunPersistentExec failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunOpenShell(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint columns,
    jint rows) {
    try {
        if (columns <= 0 || rows <= 0) {
            throw std::invalid_argument("invalid pty columns/rows");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenShellOperation>(
            static_cast<unsigned int>(columns),
            static_cast<unsigned int>(rows));
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit open shell");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunOpenShell failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunOpenPtyExec(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jcommand,
    jint columns,
    jint rows) {
    try {
        const std::string command = jstringToString(env, jcommand);
        if (command.empty()) throw std::invalid_argument("command must not be empty");
        if (columns <= 0 || rows <= 0) {
            throw std::invalid_argument("invalid pty columns/rows");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenShellOperation>(
            static_cast<unsigned int>(columns),
            static_cast<unsigned int>(rows),
            "xterm-256color",
            command);
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit open pty exec");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunOpenPtyExec failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunShellWrite(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray jdata) {
    try {
        const std::string data = jbyteArrayToString(env, jdata);
        if (data.empty()) throw std::invalid_argument("shell data must not be empty");
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::ShellWriteOperation>(data);
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit shell write");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunShellWrite failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunShellRead(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint max_bytes,
    jlong timeout_millis) {
    try {
        if (max_bytes <= 0 || timeout_millis <= 0) {
            throw std::invalid_argument("invalid max bytes/timeout");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::ShellReadOperation>(
            static_cast<size_t>(max_bytes));
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit shell read");
        }
        std::string result;
        const bool completed = awaitRuntimeCompletionWithDeadline(
            env, session, submit, std::chrono::milliseconds(timeout_millis),
            &result);
        if (env->ExceptionCheck()) return nullptr;
        if (!completed) return nullptr;
        return stringToJByteArray(env, result);
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunShellRead failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunShellResize(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jint columns,
    jint rows) {
    try {
        if (columns <= 0 || rows <= 0) {
            throw std::invalid_argument("invalid pty columns/rows");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::ShellResizeOperation>(
            static_cast<unsigned int>(columns),
            static_cast<unsigned int>(rows));
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit shell resize");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunShellResize failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunCloseShell(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::CloseShellOperation>();
        const auto submit = session->submit(std::move(operation));
        if (!submit) {
            throw std::runtime_error("failed to submit close shell");
        }
        const std::string result = awaitRuntimeCompletion(env, session, submit);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunCloseShell failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunDirectPasswordExec(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jhost,
    jint jport,
    jstring jusername,
    jstring jpassword,
    jstring jcommand,
    jstring jexpected_fingerprint,
    jlong connect_timeout_millis,
    jlong exec_timeout_millis,
    jint max_output_bytes) {
    try {
        const std::string host = jstringToString(env, jhost);
        const std::string username = jstringToString(env, jusername);
        const std::string password = jstringToString(env, jpassword);
        const std::string command = jstringToString(env, jcommand);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (host.empty() || username.empty() || password.empty() || command.empty()) {
            throw std::invalid_argument("host/username/password/command must not be empty");
        }
        if (jport <= 0 || jport > 65535 || connect_timeout_millis <= 0 ||
            exec_timeout_millis <= 0 || max_output_bytes <= 0) {
            throw std::invalid_argument("invalid port/timeout/max output");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::TcpPasswordExecOperation>(
            host, static_cast<uint16_t>(jport), username, password, command,
            std::chrono::milliseconds(connect_timeout_millis),
            static_cast<size_t>(max_output_bytes), expected_fingerprint);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(exec_timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit direct password exec");
        }
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, /*map_timeout_to_exit_124=*/true);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunDirectPasswordExec failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunDirectPrivateKeyExec(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jhost,
    jint jport,
    jstring jusername,
    jbyteArray jprivateKey,
    jstring jpassphrase,
    jstring jcommand,
    jstring jexpected_fingerprint,
    jlong connect_timeout_millis,
    jlong exec_timeout_millis,
    jint max_output_bytes) {
    try {
        const std::string host = jstringToString(env, jhost);
        const std::string username = jstringToString(env, jusername);
        const std::string private_key = jbyteArrayToString(env, jprivateKey);
        const std::string passphrase = jstringToString(env, jpassphrase);
        const std::string command = jstringToString(env, jcommand);
        const std::string expected_fingerprint = jstringToString(env, jexpected_fingerprint);
        if (host.empty() || username.empty() || private_key.empty() || command.empty()) {
            throw std::invalid_argument("host/username/private key/command must not be empty");
        }
        if (jport <= 0 || jport > 65535 || connect_timeout_millis <= 0 ||
            exec_timeout_millis <= 0 || max_output_bytes <= 0) {
            throw std::invalid_argument("invalid port/timeout/max output");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::TcpPrivateKeyExecOperation>(
            host, static_cast<uint16_t>(jport), username, private_key, passphrase,
            command, std::chrono::milliseconds(connect_timeout_millis),
            static_cast<size_t>(max_output_bytes), expected_fingerprint);
        sshnative::RequestOptions options;
        options.deadline = sshnative::MonoClock::now() +
            std::chrono::milliseconds(exec_timeout_millis);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) {
            throw std::runtime_error("failed to submit direct private key exec");
        }
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, /*map_timeout_to_exit_124=*/true);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.what());
        }
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunDirectPrivateKeyExec failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeVersion(
    JNIEnv* env,
    jobject /* thiz */) {
    try {
        const std::string version = versionString();
        return env->NewStringUTF(version.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeVersion failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeCapabilities(
    JNIEnv* env,
    jobject /* thiz */) {
    try {
        const std::string capabilities = capabilitiesString();
        return env->NewStringUTF(capabilities.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeCapabilities failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */) {
    try {
        auto session = sshnative::createSession();
        if (!session) {
            throwOutOfMemory(env);
            return 0;
        }
        const jlong handle = gSshRegistry.insert(std::move(session));
        if (handle == 0) {
            throwIllegalState(env, "failed to allocate SSH native handle");
            return 0;
        }
        return handle;
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return 0;
    } catch (...) {
        throwIllegalState(env, "nativeCreate failed");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeClose(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    if (handle == 0) {
        return;
    }
    try {
        // Remove first so no new JNI calls can reach the closing session.
        // Runtime shutdown cancels outstanding work and closes owned resources.
        auto removed = gSshRegistry.remove(handle);
        if (removed) {
            removed->shutdown();
        }
    } catch (...) {
        throwIllegalState(env, "nativeClose failed");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeCreateSftpClient(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::OpenSftpClientOperation>();
        sshnative::RequestOptions options;
        options.priority = sshnative::Priority::kNormal;
        options.deadline = sshnative::MonoClock::now() + std::chrono::seconds(15);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) throw std::runtime_error("failed to create SFTP client");
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, false, true);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throwIllegalState(env, error.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeCreateSftpClient failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeCloseSftpClient(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong client_handle) {
    try {
        if (client_handle <= 0) return;
        const auto session = gSshRegistry.get(handle);
        if (!session) return;
        auto operation = std::make_unique<sshnative::CloseSftpClientOperation>(
            static_cast<sshnative::ResourceId>(client_handle));
        sshnative::RequestOptions options;
        options.priority = sshnative::Priority::kNormal;
        options.deadline = sshnative::MonoClock::now() + std::chrono::seconds(5);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) return;
        awaitRuntimeCompletion(env, session, submit, false, true);
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throwIllegalState(env, error.what());
    } catch (...) {
        throwIllegalState(env, "nativeCloseSftpClient failed");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunSftpCommand(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong client_handle,
    jint command,
    jstring jpath,
    jstring jtarget,
    jlong value) {
    try {
        const std::string path = jstringToUtf8(env, jpath);
        const std::string target = jstringToUtf8(env, jtarget);
        if (client_handle <= 0 || path.empty() || path.size() > 32768 ||
            target.size() > 32768 || value < 0) {
            throw std::invalid_argument("invalid SFTP path/value");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        std::unique_ptr<sshnative::Operation> operation;
        switch (command) {
            case 0:
                operation = std::make_unique<sshnative::SftpListOperation>(
                    path, static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 1:
                operation = std::make_unique<sshnative::SftpRealPathOperation>(
                    path, static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 2:
                operation = std::make_unique<sshnative::SftpStatOperation>(
                    path, value != 0, static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 3:
                operation = std::make_unique<sshnative::SftpMkdirOperation>(
                    path, static_cast<long>(value),
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 4:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kRename, path, target, 0,
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 5:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kUnlink, path, "", 0,
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 6:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kRmdir, path, "", 0,
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 7:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kChmod, path, std::string(),
                    static_cast<uint64_t>(value),
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 8:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kChown, path, std::string(),
                    static_cast<uint64_t>(value),
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 9:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kChgrp, path, std::string(),
                    static_cast<uint64_t>(value),
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 10:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kSymlink, path, target, 0,
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 11:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kReadlink, path, "", 0,
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            case 12:
                operation = std::make_unique<sshnative::SftpCommandOperation>(
                    sshnative::SftpCommand::kStatVfs, path, "", 0,
                    static_cast<sshnative::ResourceId>(client_handle));
                break;
            default:
                throw std::invalid_argument("unknown SFTP command");
        }
        sshnative::RequestOptions options;
        options.priority = sshnative::Priority::kNormal;
        options.deadline = sshnative::MonoClock::now() + std::chrono::seconds(15);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) throw std::runtime_error("failed to submit SFTP command");
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, false, true);
        if (env->ExceptionCheck()) return nullptr;
        return utf8ToJString(env, result);
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throwIllegalState(env, error.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunSftpCommand failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunSftpOpen(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong client_handle,
    jstring jpath,
    jlong offset,
    jboolean write,
    jboolean truncate) {
    try {
        const std::string path = jstringToUtf8(env, jpath);
        if (client_handle <= 0 || path.empty() || path.size() > 32768 || offset < 0) {
            throw std::invalid_argument("invalid SFTP path/offset");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::SftpOpenFileOperation>(
            path, static_cast<uint64_t>(offset), write == JNI_TRUE,
            truncate == JNI_TRUE,
            static_cast<sshnative::ResourceId>(client_handle));
        sshnative::RequestOptions options;
        options.priority = sshnative::Priority::kBulk;
        options.deadline = sshnative::MonoClock::now() + std::chrono::seconds(15);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) throw std::runtime_error("failed to submit SFTP open");
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, false, true);
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throwIllegalState(env, error.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunSftpOpen failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunSftpRead(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong file_handle,
    jint max_bytes) {
    try {
        if (file_handle <= 0 || max_bytes <= 0 || max_bytes > 256 * 1024) {
            throw std::invalid_argument("invalid SFTP file handle/read size");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        auto operation = std::make_unique<sshnative::SftpHandleReadOperation>(
            static_cast<sshnative::ResourceId>(file_handle),
            static_cast<size_t>(max_bytes));
        sshnative::RequestOptions options;
        options.priority = sshnative::Priority::kBulk;
        options.deadline = sshnative::MonoClock::now() + std::chrono::seconds(15);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) throw std::runtime_error("failed to submit SFTP read");
        const std::string result = awaitRuntimeCompletion(
            env, session, submit, false, true);
        if (env->ExceptionCheck()) return nullptr;
        return stringToJByteArray(env, result);
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throwIllegalState(env, error.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeRunSftpRead failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunSftpWrite(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong file_handle,
    jbyteArray jdata) {
    try {
        const std::string data = jbyteArrayToString(env, jdata);
        if (file_handle <= 0 || data.size() > 256 * 1024) {
            throw std::invalid_argument("invalid SFTP file handle/write size");
        }
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return -1;
        }
        auto operation = std::make_unique<sshnative::SftpHandleWriteOperation>(
            static_cast<sshnative::ResourceId>(file_handle), data);
        sshnative::RequestOptions options;
        options.priority = sshnative::Priority::kBulk;
        options.deadline = sshnative::MonoClock::now() + std::chrono::seconds(15);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) throw std::runtime_error("failed to submit SFTP write");
        awaitRuntimeCompletion(env, session, submit, false, true);
        if (env->ExceptionCheck()) return -1;
        return static_cast<jint>(data.size());
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return -1;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throwIllegalState(env, error.what());
        return -1;
    } catch (...) {
        throwIllegalState(env, "nativeRunSftpWrite failed");
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeRunSftpClose(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong file_handle) {
    try {
        if (file_handle <= 0) return;
        const auto session = gSshRegistry.get(handle);
        if (!session) return;
        auto operation = std::make_unique<sshnative::SftpCloseHandleOperation>(
            static_cast<sshnative::ResourceId>(file_handle));
        sshnative::RequestOptions options;
        options.priority = sshnative::Priority::kBulk;
        options.deadline = sshnative::MonoClock::now() + std::chrono::seconds(5);
        const auto submit = session->submit(std::move(operation), options);
        if (!submit) return;
        awaitRuntimeCompletion(env, session, submit, false, true);
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throwIllegalState(env, error.what());
    } catch (...) {
        throwIllegalState(env, "nativeRunSftpClose failed");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeCancel(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong request_id) {
    try {
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return JNI_FALSE;
        }
        return session->cancel(static_cast<sshnative::RequestId>(request_id))
            ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        throwIllegalState(env, "nativeCancel failed");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeAwaitEvent(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong timeout_millis) {
    try {
        const auto session = gSshRegistry.get(handle);
        if (!session) {
            throwIllegalState(env, "SSH native handle is closed");
            return nullptr;
        }
        sshnative::RuntimeEvent event;
        if (!session->waitEvent(&event, std::chrono::milliseconds(timeout_millis))) {
            return nullptr;
        }
        return newRuntimeEvent(env, event);
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env);
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "nativeAwaitEvent failed");
        return nullptr;
    }
}

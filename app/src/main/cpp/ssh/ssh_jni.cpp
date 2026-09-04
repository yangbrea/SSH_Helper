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
#include <memory>
#include <new>
#include <string>

#include "../handle_registry.h"
#include "ssh_error.h"
#include "ssh_libssh2.h"
#include "ssh_runtime.h"
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

std::string jbyteArrayToString(JNIEnv* env, jbyteArray value) {
    if (value == nullptr) return std::string();
    const jsize length = env->GetArrayLength(value);
    if (length <= 0) return std::string();
    std::string result;
    result.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(&result[0]));
    return result;
}

} // namespace
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    return JNI_VERSION_1_6;
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeConnectExec(
    JNIEnv* env,
    jobject /* thiz */,
    jstring jhost,
    jint jport,
    jstring jusername,
    jstring jpassword,
    jstring jcommand) {
    try {
        const std::string host = jstringToString(env, jhost);
        const std::string username = jstringToString(env, jusername);
        const std::string password = jstringToString(env, jpassword);
        const std::string command = jstringToString(env, jcommand);
        if (host.empty() || username.empty() || password.empty() || command.empty()) {
            throw std::invalid_argument("host/username/password/command must not be empty");
        }
        if (jport <= 0 || jport > 65535) {
            throw std::invalid_argument("port out of range");
        }

        const int fd = sshnative::connectTcp(host, static_cast<uint16_t>(jport), std::chrono::seconds(10));
        sshnative::Libssh2Session session;
        session.setBlocking(true);
        session.handshake(fd);
        const bool authed = session.passwordAuth(username, password);
        if (!authed) {
            sshnative::closeFd(fd);
            throw std::runtime_error("SSH authentication failed");
        }
        std::string output;
        const int exit_code = session.execCommand(command, output);
        sshnative::closeFd(fd);
        const std::string result = "exit=" + std::to_string(exit_code) + "\n" + output;
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
        throwIllegalState(env, "nativeConnectExec failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yang136_sshhelper_ssh_native_NativeSshBridge_nativeConnectExecWithPrivateKey(
    JNIEnv* env,
    jobject /* thiz */,
    jstring jhost,
    jint jport,
    jstring jusername,
    jbyteArray jprivateKey,
    jstring jpassphrase,
    jstring jcommand) {
    try {
        const std::string host = jstringToString(env, jhost);
        const std::string username = jstringToString(env, jusername);
        const std::string private_key = jbyteArrayToString(env, jprivateKey);
        const std::string passphrase = jstringToString(env, jpassphrase);
        const std::string command = jstringToString(env, jcommand);
        if (host.empty() || username.empty() || private_key.empty() || command.empty()) {
            throw std::invalid_argument("host/username/private key/command must not be empty");
        }
        if (jport <= 0 || jport > 65535) {
            throw std::invalid_argument("port out of range");
        }

        const int fd = sshnative::connectTcp(host, static_cast<uint16_t>(jport), std::chrono::seconds(10));
        sshnative::Libssh2Session session;
        session.setBlocking(true);
        session.handshake(fd);
        const bool authed = session.publicKeyAuth(username, private_key, passphrase);
        if (!authed) {
            sshnative::closeFd(fd);
            throw std::runtime_error("SSH public key authentication failed");
        }
        std::string output;
        const int exit_code = session.execCommand(command, output);
        sshnative::closeFd(fd);
        const std::string result = "exit=" + std::to_string(exit_code) + "\n" + output;
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
        throwIllegalState(env, "nativeConnectExecWithPrivateKey failed");
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
        // Stop the owner event loop, run already-queued work, then drop the
        // registry reference. Repeated/unknown/0 handles remain safe no-ops.
        auto removed = gSshRegistry.remove(handle);
        if (removed) {
            removed->shutdown();
        }
    } catch (...) {
        throwIllegalState(env, "nativeClose failed");
    }
}

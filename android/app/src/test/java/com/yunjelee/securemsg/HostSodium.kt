package com.yunjelee.securemsg

import java.io.File

/**
 * Host libsodium for JVM unit tests.
 *
 * CryptoUtil binds to lazysodium-android, which ships libsodium for Android
 * ABIs only; on the host JVM the library has to come from the machine. JNA
 * resolves it through `jna.library.path`, set here before the first CryptoUtil
 * call — its LazySodium instance is lazy, so the property is in place in time.
 *
 * [available] is false where no host libsodium exists (a bare CI image with no
 * libsodium package). Crypto tests assume on it instead of reporting a missing
 * system package as a product failure; the format and guard tests below it need
 * no native code and always run.
 */
object HostSodium {

    private val CANDIDATE_DIRS = listOf(
        "/opt/homebrew/lib", // Homebrew, Apple silicon
        "/usr/local/lib", // Homebrew on Intel, manual installs
        "/usr/lib/x86_64-linux-gnu", // Debian/Ubuntu
        "/usr/lib/aarch64-linux-gnu",
        "/usr/lib64",
        "/usr/lib",
    )

    val available: Boolean by lazy {
        System.setProperty("jna.library.path", CANDIDATE_DIRS.joinToString(File.pathSeparator))
        runCatching { CryptoUtil.generateKeypair() }.isSuccess
    }
}

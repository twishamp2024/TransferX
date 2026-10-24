package com.transferx.core.security

import com.transferx.core.util.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing codes: 12 Crockford-base32 characters (60 bits), shown as `ABCD-EFGH-JKMN`.
 *
 * Proofs are HMAC-SHA256(key = code, "TransferX-pair-v1" ‖ role ‖ clientFp ‖ serverFp).
 * Binding both TLS certificate fingerprints into the MAC means a man-in-the-middle
 * terminating two separate TLS sessions can't relay a valid proof. Offline guessing
 * would need ~2^59 HMAC evaluations within the code's 5-minute lifetime.
 */
object PairingCodes {
    const val LENGTH = 12
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(): String = buildString {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun format(code: String): String = code.chunked(4).joinToString("-")

    /** Accepts user input with dashes, spaces, lowercase, and the usual O/0, I/L/1 confusions. */
    fun normalize(input: String): String? {
        val s = buildString {
            for (c in input.uppercase()) {
                when (c) {
                    '-', ' ', '\t' -> {}
                    'O' -> append('0')
                    'I', 'L' -> append('1')
                    else -> append(c)
                }
            }
        }
        if (s.length != LENGTH || s.any { it !in ALPHABET }) return null
        return s
    }

    fun proof(code: String, role: Role, clientFingerprintHex: String, serverFingerprintHex: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        mac.update("TransferX-pair-v1".toByteArray(Charsets.US_ASCII))
        mac.update(0)
        mac.update(role.name.toByteArray(Charsets.US_ASCII))
        mac.update(0)
        mac.update(Hex.decode(clientFingerprintHex))
        mac.update(Hex.decode(serverFingerprintHex))
        return Hex.encode(mac.doFinal())
    }

    enum class Role { CLIENT, SERVER }
}

/**
 * Holds the receiver's current pairing code. Codes expire after [ttlMs], are rotated
 * after a successful pairing, and are invalidated after [maxAttempts] wrong proofs.
 */
class PairingCodeManager(
    private val ttlMs: Long,
    private val maxAttempts: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class ActiveCode(val code: String, val expiresAt: Long, val failures: Int) {
        val display: String get() = PairingCodes.format(code)
    }

    data class VerifyResult(val code: String?, val attemptsRemaining: Int)

    private val _current = MutableStateFlow<ActiveCode?>(null)

    /** Observed by the UI to display the code and its countdown. Null until requested. */
    val current: StateFlow<ActiveCode?> = _current.asStateFlow()

    @Synchronized
    fun activeCode(): ActiveCode {
        val c = _current.value
        if (c != null && clock() < c.expiresAt && c.failures < maxAttempts) return c
        return rotateLocked()
    }

    @Synchronized
    fun rotate(): ActiveCode = rotateLocked()

    @Synchronized
    fun clear() {
        _current.value = null
    }

    /**
     * [matches] is called with the current code and must compare proofs in constant time.
     * A successful verification consumes the code.
     */
    @Synchronized
    fun verify(matches: (String) -> Boolean): VerifyResult {
        val c = _current.value
        if (c == null || clock() >= c.expiresAt || c.failures >= maxAttempts) {
            return VerifyResult(null, 0)
        }
        if (matches(c.code)) {
            rotateLocked()
            return VerifyResult(c.code, maxAttempts)
        }
        val failed = c.copy(failures = c.failures + 1)
        _current.value = failed
        val remaining = maxAttempts - failed.failures
        if (remaining <= 0) rotateLocked()
        return VerifyResult(null, remaining.coerceAtLeast(0))
    }

    fun attemptsRemaining(): Int = (maxAttempts - (_current.value?.failures ?: 0)).coerceAtLeast(0)

    private fun rotateLocked(): ActiveCode {
        val c = ActiveCode(PairingCodes.generate(), clock() + ttlMs, 0)
        _current.value = c
        return c
    }
}

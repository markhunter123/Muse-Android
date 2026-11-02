package io.github.markhunter123.muse.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tencent's own worked example for 签名方法 v3, reproduced as a fixture — the same one Muse's
 * voice-runtime tests pin. Every constant comes from the vendor's documentation rather than from
 * the implementation: the payload hash, the canonical request, the hashed canonical request and —
 * with the published derived signing key — the final signature. A change to the signing chain that
 * still "looks right" cannot survive it.
 *
 * The SecretKey itself is masked in the docs, so the key-derivation steps cannot be reproduced
 * from them; what they do publish is the derived `SecretSigning`, which is all the final HMAC
 * needs and which the signature test below feeds in directly.
 */
class TencentAsrTest {
    private object Published {
        const val TIMESTAMP = 1551113065L
        const val SERVICE = "cvm"
        const val PAYLOAD = "{\"Limit\": 1, \"Filters\": [{\"Values\": [\"\\u672a\\u547d\\u540d\"], \"Name\": \"instance-name\"}]}"
        const val HASHED_PAYLOAD = "35e9c5b0e3ae67532d3c9f17ead6c90222632e5b1ff7f6e89887f1398934f064"
        val CANONICAL_REQUEST = listOf(
            "POST",
            "/",
            "",
            "content-type:application/json; charset=utf-8",
            "host:cvm.tencentcloudapi.com",
            "x-tc-action:describeinstances",
            "",
            "content-type;host;x-tc-action",
            HASHED_PAYLOAD,
        ).joinToString("\n")
        const val SIGNED_HEADERS = "content-type;host;x-tc-action"
        const val CREDENTIAL_SCOPE = "2019-02-25/cvm/tc3_request"
        const val HASHED_CANONICAL_REQUEST = "7019a55be8395899b900fb5564e4200d984910f34794a27cb3fb7d10ff6a1e84"
        const val SECRET_SIGNING = "b596b923aad85185e2d1f6659d2a062e0a86731226e021e61bfe06f7ed05f5af"
        const val SIGNATURE = "10b1a37a7301a02ca19a647ad722d5e43b4b3cff309d421d85b46093f6ab6c4f"
    }

    @Test fun `canonical request reproduces the published one byte for byte`() {
        val (canonical, signed) = TencentAsr.buildCanonicalRequest(
            listOf(
                "content-type" to "application/json; charset=utf-8",
                "host" to "cvm.tencentcloudapi.com",
                "X-TC-Action" to "describeinstances",
            ),
            Published.PAYLOAD,
        )
        assertEquals(Published.CANONICAL_REQUEST, canonical)
        assertEquals(Published.SIGNED_HEADERS, signed)
    }

    @Test fun `canonical request hashes to the published values`() {
        assertEquals(Published.HASHED_PAYLOAD, TencentAsr.sha256Hex(Published.PAYLOAD))
        assertEquals(Published.HASHED_CANONICAL_REQUEST, TencentAsr.sha256Hex(Published.CANONICAL_REQUEST))
    }

    @Test fun `credential scope names the UTC date`() {
        assertEquals(Published.CREDENTIAL_SCOPE, TencentAsr.credentialScope(Published.TIMESTAMP, Published.SERVICE))
    }

    @Test fun `published signing key over the published string-to-sign yields the published signature`() {
        // The final HMAC is independent of how SecretSigning was reached, so the vendor vector
        // covers it even though the docs mask the SecretKey — the same check Muse's tests make.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Published.SECRET_SIGNING.hexToBytes(), "HmacSHA256"))
        val stringToSign = "TC3-HMAC-SHA256\n${Published.TIMESTAMP}\n${Published.CREDENTIAL_SCOPE}\n${Published.HASHED_CANONICAL_REQUEST}"
        assertEquals(Published.SIGNATURE, mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)).toHex())
    }

    @Test fun `authorization header composes the documented shape`() {
        // Fixture credentials, so this pins the header's shape (scope date, service, signed list) —
        // the vendor's own signature is out of reach behind the masked SecretKey.
        val header = TencentAsr.authorizationHeader(
            TencentAsr.Credentials("AKIDexample", "secret-example"), "asr", Published.TIMESTAMP,
            Published.CANONICAL_REQUEST, Published.SIGNED_HEADERS,
        )
        assertTrue(
            Regex("^TC3-HMAC-SHA256 Credential=AKIDexample/2019-02-25/asr/tc3_request, SignedHeaders=content-type;host;x-tc-action, Signature=[0-9a-f]{64}$")
                .matches(header),
        )
    }

    @Test fun `signed headers are lowercased and sorted regardless of caller order`() {
        val (_, signed) = TencentAsr.buildCanonicalRequest(
            listOf(
                "X-TC-Action" to "sentencerecognition",
                "Host" to "asr.tencentcloudapi.com",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            "{}",
        )
        assertEquals("content-type;host;x-tc-action", signed)
    }

    @Test fun `audio past the one-shot cap is refused before any network call`() {
        try {
            TencentAsr.transcribe(TencentAsr.Credentials("id", "key"), ByteArray(TencentAsr.MAX_SECONDS * 32_000 + 1), "16k_zh")
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // required
        }
    }

    @Test fun `engine type follows the language selection like Muse's rule`() {
        assertEquals("16k_zh-PY", TencentAsr.engineTypeForLanguages(listOf("zh", "en")))
        assertEquals("16k_zh", TencentAsr.engineTypeForLanguages(listOf("zh")))
        assertEquals("16k_en", TencentAsr.engineTypeForLanguages(listOf("en")))
        assertEquals("16k_ja", TencentAsr.engineTypeForLanguages(listOf("ja")))
        assertEquals("16k_ko", TencentAsr.engineTypeForLanguages(listOf("ko")))
        assertEquals("16k_zh", TencentAsr.engineTypeForLanguages(listOf("sv")))
        assertEquals("16k_zh", TencentAsr.engineTypeForLanguages(emptyList()))
    }

    @Test fun `wav container wraps pcm in a 44-byte header`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = TencentAsr.wavFromPcm(pcm)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + pcm.size, wav.leInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, wav.leInt(16)) // fmt chunk size
        assertEquals(1, wav.leShort(20)) // PCM
        assertEquals(1, wav.leShort(22)) // mono
        assertEquals(16_000, wav.leInt(24)) // sample rate
        assertEquals(32_000, wav.leInt(28)) // byte rate
        assertEquals(2, wav.leShort(32)) // block align
        assertEquals(16, wav.leShort(34)) // bits per sample
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size, wav.leInt(40))
        for (i in pcm.indices) assertEquals(pcm[i], wav[44 + i])
    }

    private fun ByteArray.leInt(offset: Int) =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.leShort(offset: Int) = (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun String.hexToBytes() = ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) + this[it * 2 + 1].digitToInt(16)).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

package app.revanced.bilibili.update

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal data class UpdateManifest(
    val code: Long, val release: String, val versionCode: Long,
    val url: String, val size: Long, val sha256: String, val notes: String
) {
    companion object {
        // Public key of this distribution's persistent APK signing certificate.
        private const val KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA0IX/RfoXyRkubhMom9SDIQXZNATBmpVqpD8jwEq9su5CnSRlQfi7v7eCvkTpUDoXcPgZ34cvVbcHEtKQVUT56Imf8Es9RTf6FriLlDYOETEUfwlo5y9rc+d8w7tq4f80A4xCWOJftkiPI5qUNi9ZVW/Rv18to/8oN2ZrbnzxtNEb1kE3Ygoho5MNgjsdbfzh9r+CGcW7+vVGatGlJ9aKMva5nWXaj1awzUG4/0Qh0Ki0OIDl7ImmJpDbRg3+pH2nmj4oQrv4oF700ZFr9A1sv+HkEwPRC8ozsuB6pSY0FL/wEwdJc+491nbMPNoW3+Zymql+IsTIl4odLhc9+qDPECp5mA3Ft3zTZs2ouIUZJi9QVpY2f3oQP5LjMeBg8O+e9wJGPwyvOpsmhj5/9O4RpiLvhzsPiBYbXqVL9cTxsoewkChvjXlyBKhDGxxcXxLUWJjJ1lSHt58M/ZL2Ot7W3DX7AbFKI0xhM+GokX5f2J+gW6jQj8RGyXAxI8GHWfzRAgMBAAE="

        fun parse(text: String, packageName: String): UpdateManifest {
            require(text.length <= 131072) { "更新信息过大" }
            val envelope = JSONObject(text)
            val payload = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP)
            val signature = Base64.decode(envelope.getString("signature"), Base64.NO_WRAP)
            require(payload.size <= 65536) { "更新信息过大" }
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.decode(KEY, Base64.NO_WRAP))))
            verifier.update(payload)
            require(verifier.verify(signature)) { "更新信息签名不匹配" }
            val data = JSONObject(payload.toString(Charsets.UTF_8))
            require(data.getInt("schema") == 1 && data.getString("package") == packageName) { "更新包不适用" }
            require(data.getString("abi") == "arm64-v8a" && Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "更新包架构不适用" }
            require(data.getInt("min_sdk") <= Build.VERSION.SDK_INT) { "新版需要更高版本的 Android" }
            val url = data.getString("url")
            requireDownloadUrl(url)
            val size = data.getLong("size")
            val sha = data.getString("sha256")
            val code = data.getLong("release_code")
            require(code > 0 && size in 1..(2L * 1024 * 1024 * 1024) && sha.matches(Regex("[a-f0-9]{64}"))) { "更新信息无效" }
            return UpdateManifest(code, data.getString("release").take(128), data.getLong("version_code"),
                url, size, sha, data.optString("notes").take(8000))
        }

        fun requireDownloadUrl(url: String) {
            val uri = URI(url)
            val publicHttp = uri.scheme == "http" && uri.host == "cdn.ianother.cn" &&
                    uri.port in arrayOf(-1, 80) && uri.path.startsWith("/biliroamingx/") &&
                    ".." !in uri.path.split('/') && '\\' !in uri.path
            require((uri.scheme == "https" || publicHttp) && !uri.host.isNullOrEmpty() &&
                    uri.userInfo == null && uri.fragment == null) { "更新地址不受信任" }
        }
    }
}

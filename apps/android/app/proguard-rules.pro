# kotlinx.serialization keeps generated serializers via its bundled rules.
# BouncyCastle: only the lightweight X25519 / ChaCha20-Poly1305 classes are used; nothing reflective.
-dontwarn org.bouncycastle.**

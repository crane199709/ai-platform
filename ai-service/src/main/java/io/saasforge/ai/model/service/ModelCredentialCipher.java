package io.saasforge.ai.model.service;

import io.saasforge.ai.model.config.ModelCredentialProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** 模型凭据只在写入目录与发起供应商请求的边界加解密，AAD 防止密文跨目录行替换。 */
@Service
@EnableConfigurationProperties(ModelCredentialProperties.class)
public class ModelCredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ModelCredentialCipher(ModelCredentialProperties properties) {
        key = new SecretKeySpec(Base64.getDecoder().decode(properties.key()), "AES");
    }

    /** 加密直接密钥，并绑定其所属模型目录 ID。 */
    public String encrypt(String plaintext, UUID modelId) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, modelId);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:"
                    + Base64.getEncoder()
                            .encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                                    .put(nonce)
                                    .put(encrypted)
                                    .array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Model credential unavailable");
        }
    }

    /** 只允许原模型目录项恢复密钥；格式、密钥或认证失败均不暴露原因。 */
    public String decrypt(String ciphertext, UUID modelId) {
        try {
            if (ciphertext == null || !ciphertext.startsWith("v1:")) {
                throw new IllegalArgumentException();
            }
            var bytes = ByteBuffer.wrap(Base64.getDecoder().decode(ciphertext.substring(3)));
            if (bytes.remaining() < 28) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = new byte[12];
            bytes.get(nonce);
            byte[] encrypted = new byte[bytes.remaining()];
            bytes.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, modelId).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Model credential unavailable");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, UUID modelId) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(modelId.toString().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}

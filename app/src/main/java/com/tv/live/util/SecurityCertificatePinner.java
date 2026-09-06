package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import com.tv.live.util.LogBridge;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;

public class SecurityCertificatePinner {

    private static final String TAG = "SSLCertPinner";
    private static final String PREFS_NAME = "ssl_cert_store";
    private static final long CERT_VALIDITY_CHECK_INTERVAL = 24 * 60 * 60 * 1000L;

    private static volatile SecurityCertificatePinner sInstance;
    private Context mContext;
    private SharedPreferences mCertStore;

    private final ConcurrentHashMap<String, List<String>> mCertPins = new ConcurrentHashMap<>();

    private final List<CertChangeListener> mListeners = new ArrayList<>();

    private VerificationMode mMode = VerificationMode.LEARN;

    private int mTotalVerifications = 0;
    private int mFailedVerifications = 0;
    private long mLastVerificationTime = 0;

    public enum VerificationMode {

        LEARN,

        VERIFY,

        WARN_ONLY,

        DISABLED
    }

    private SecurityCertificatePinner() {
    }

    public static SecurityCertificatePinner getInstance() {
        if (sInstance == null) {
            synchronized (SecurityCertificatePinner.class) {
                if (sInstance == null) {
                    sInstance = new SecurityCertificatePinner();
                }
            }
        }
        return sInstance;
    }

    public void init(Context context) {
        mContext = context.getApplicationContext();
        mCertStore = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadCertPinsFromStorage();
        LogBridge.i(TAG, "SSL证书管理器初始化完成，已存储 " + mCertPins.size() + " 个域名证书");

        if (mCertPins.isEmpty()) {
            mMode = VerificationMode.LEARN;
            LogBridge.i(TAG, "首次启动，启用学习模式");
        } else {
            mMode = VerificationMode.WARN_ONLY;
            LogBridge.i(TAG, "已有证书数据，启用警告模式");
        }
    }

    private void loadCertPinsFromStorage() {
        if (mCertStore == null) return;

        Map<String, ?> allPrefs = mCertStore.getAll();
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key.startsWith("pin:") && value instanceof String) {
                String domain = key.substring(4);
                String pinsStr = (String) value;
                List<String> pins = parsePinsFromString(pinsStr);
                if (!pins.isEmpty()) {
                    mCertPins.put(domain, pins);
                }
            }
        }
    }

    private void saveCertPin(String domain, List<String> pins) {
        if (mCertStore == null) return;

        String key = "pin:" + domain;
        String value = serializePins(pins);
        mCertStore.edit().putString(key, value).apply();
        mCertPins.put(domain, pins);

        LogBridge.i(TAG, "保存证书指纹: " + domain + " -> " + pins.size() + " 个pin");
    }

    private String serializePins(List<String> pins) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pins.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(pins.get(i));
        }
        return sb.toString();
    }

    private List<String> parsePinsFromString(String str) {
        List<String> pins = new ArrayList<>();
        if (str == null || str.isEmpty()) return pins;

        String[] parts = str.split("\\|");
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                pins.add(part);
            }
        }
        return pins;
    }

    public void addCertChangeListener(CertChangeListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeCertChangeListener(CertChangeListener listener) {
        mListeners.remove(listener);
    }

    public void setMode(VerificationMode mode) {
        mMode = mode;
        LogBridge.i(TAG, "证书验证模式: " + mode.name());
    }

    public VerificationMode getMode() {
        return mMode;
    }

    public List<String> getCertPins(String domain) {
        List<String> pins = mCertPins.get(domain);
        return pins != null ? pins : Collections.emptyList();
    }

    public boolean hasCertPin(String domain) {
        return mCertPins.containsKey(domain);
    }

    public void removeCertPin(String domain) {
        if (mCertStore != null) {
            mCertStore.edit().remove("pin:" + domain).apply();
        }
        mCertPins.remove(domain);
        LogBridge.i(TAG, "移除证书指纹: " + domain);
    }

    public void clearAllCertPins() {
        if (mCertStore != null) {
            mCertStore.edit().clear().apply();
        }
        mCertPins.clear();
        LogBridge.i(TAG, "清除所有证书指纹");
    }

    public CertVerificationResult processServerCertificates(String hostname, X509Certificate[] certs) {
        if (mMode == VerificationMode.DISABLED) {
            return CertVerificationResult.SUCCESS;
        }

        mTotalVerifications++;
        mLastVerificationTime = System.currentTimeMillis();

        if (certs == null || certs.length == 0) {
            LogBridge.w(TAG, "证书链为空: " + hostname);
            return CertVerificationResult.FAIL;
        }

        List<String> certPins = calculateCertPins(certs);
        if (certPins.isEmpty()) {
            return CertVerificationResult.FAIL;
        }

        List<String> storedPins = mCertPins.get(hostname);

        if (storedPins == null || storedPins.isEmpty()) {

            if (mMode == VerificationMode.LEARN || mMode == VerificationMode.WARN_ONLY) {
                saveCertPin(hostname, certPins);
                LogBridge.i(TAG, "首次学习证书: " + hostname);
                notifyCertChanged(hostname, null, certPins, true);
                return CertVerificationResult.SUCCESS;
            } else if (mMode == VerificationMode.VERIFY) {
                LogBridge.e(TAG, "证书未存储，拒绝连接: " + hostname);
                mFailedVerifications++;
                return CertVerificationResult.FAIL;
            }
        } else {

            boolean matches = certPins.stream().anyMatch(storedPins::contains);

            if (matches) {
                return CertVerificationResult.SUCCESS;
            } else {

                mFailedVerifications++;

                if (mMode == VerificationMode.LEARN) {

                    LogBridge.w(TAG, "证书变更，自动更新: " + hostname);
                    saveCertPin(hostname, certPins);
                    notifyCertChanged(hostname, storedPins, certPins, true);
                    return CertVerificationResult.SUCCESS;
                } else if (mMode == VerificationMode.WARN_ONLY) {

                    LogBridge.w(TAG, "证书不匹配，但允许连接: " + hostname);
                    LogBridge.w(TAG, "  存储的证书: " + storedPins);
                    LogBridge.w(TAG, "  当前证书: " + certPins);
                    notifyCertChanged(hostname, storedPins, certPins, false);
                    return CertVerificationResult.WARNING;
                } else if (mMode == VerificationMode.VERIFY) {

                    LogBridge.e(TAG, "证书不匹配，拒绝连接: " + hostname);
                    LogBridge.e(TAG, "  存储的证书: " + storedPins);
                    LogBridge.e(TAG, "  当前证书: " + certPins);
                    notifyCertChanged(hostname, storedPins, certPins, false);
                    return CertVerificationResult.FAIL;
                }
            }
        }

        return CertVerificationResult.SUCCESS;
    }

    private List<String> calculateCertPins(X509Certificate[] certs) {
        List<String> pins = new ArrayList<>();

        try {
            for (X509Certificate cert : certs) {

                byte[] pubKeyBytes = cert.getPublicKey().getEncoded();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(pubKeyBytes);
                String pin = "sha256/" + android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
                pins.add(pin);
            }
        } catch (Exception e) {
            LogBridge.e(TAG, "计算证书指纹失败: " + e.getMessage());
        }

        return pins;
    }

    private void notifyCertChanged(String hostname, List<String> oldPins, List<String> newPins, boolean trusted) {
        for (CertChangeListener listener : mListeners) {
            try {
                listener.onCertChanged(hostname, oldPins, newPins, trusted);
            } catch (Exception e) {
                LogBridge.e(TAG, "通知证书变更失败: " + e.getMessage());
            }
        }
    }

    public OkHttpClient.Builder configureOkHttpClient(OkHttpClient.Builder builder) {

        builder.sslSocketFactory(
            javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory(),
            new CertAwareTrustManager()
        );

        builder.hostnameVerifier(new CertAwareHostnameVerifier());

        return builder;
    }

    private class CertAwareTrustManager implements X509TrustManager {
        private final X509TrustManager systemTm;

        CertAwareTrustManager() {
            try {
                javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((java.security.KeyStore) null);
                javax.net.ssl.TrustManager[] tms = tmf.getTrustManagers();
                systemTm = (X509TrustManager) tms[0];
            } catch (Exception e) {
                throw new RuntimeException("初始化 TrustManager 失败", e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            systemTm.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            systemTm.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return systemTm.getAcceptedIssuers();
        }
    }

    private class CertAwareHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, SSLSession session) {

            try {
                HostnameVerifier hv = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
                if (!hv.verify(hostname, session)) {
                    LogBridge.w(TAG, "主机名验证失败: " + hostname);
                    return false;
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "主机名验证异常: " + hostname + " -> " + e.getMessage());
                return false;
            }

            try {
                Certificate[] peerCerts = session.getPeerCertificates();
                if (peerCerts != null && peerCerts.length > 0) {
                    X509Certificate[] certs = new X509Certificate[peerCerts.length];
                    for (int i = 0; i < peerCerts.length; i++) {
                        if (peerCerts[i] instanceof X509Certificate) {
                            certs[i] = (X509Certificate) peerCerts[i];
                        }
                    }
                    CertVerificationResult result = processServerCertificates(hostname, certs);

                    if (result == CertVerificationResult.FAIL) {
                        return false;
                    }
                }
            } catch (Exception e) {
                LogBridge.e(TAG, "证书处理异常: " + hostname + " -> " + e.getMessage());
                if (mMode == VerificationMode.VERIFY) {
                    return false;
                }
            }

            return true;
        }
    }

    public Map<String, Object> getSecurityStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_verifications", mTotalVerifications);
        stats.put("failed_verifications", mFailedVerifications);
        stats.put("stored_cert_domains", mCertPins.size());
        stats.put("verification_mode", mMode.name());
        stats.put("last_verification_time", mLastVerificationTime);
        stats.put("failure_rate", mTotalVerifications > 0 ?
            (double) mFailedVerifications / mTotalVerifications : 0);
        return stats;
    }

    public String exportCertInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("SSL证书存储信息:\n");
        sb.append("模式: ").append(mMode.name()).append("\n");
        sb.append("存储域名数: ").append(mCertPins.size()).append("\n");
        sb.append("验证统计: 总").append(mTotalVerifications)
          .append(", 失败").append(mFailedVerifications).append("\n\n");

        for (Map.Entry<String, List<String>> entry : mCertPins.entrySet()) {
            sb.append("域名: ").append(entry.getKey()).append("\n");
            for (String pin : entry.getValue()) {
                sb.append("  pin: ").append(pin).append("\n");
            }
        }

        return sb.toString();
    }

    public enum CertVerificationResult {

        SUCCESS,

        FAIL,

        WARNING
    }

    public interface CertChangeListener {

        void onCertChanged(String hostname, List<String> oldPins, List<String> newPins, boolean trusted);
    }
}

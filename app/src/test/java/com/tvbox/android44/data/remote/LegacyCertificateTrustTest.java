package com.tvbox.android44.data.remote;

import com.tvbox.android44.R;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * 补充根证书信任逻辑。
 * 仓库里打包的是 ISRG Root X1 + ISRG Root X2 两个公共根，
 * 两者都必须通过指纹白名单校验；任何其它根证书必须被拒绝。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19, 23, 28})
public class LegacyCertificateTrustTest {

    private InputStream pem() {
        return RuntimeEnvironment.getApplication().getResources().openRawResource(R.raw.isrg_roots);
    }

    /** 读出打包文件里的全部证书（X1 在前，X2 在后）。 */
    private List<X509Certificate> bundledRoots() throws Exception {
        List<X509Certificate> out = new ArrayList<>();
        try (InputStream in = pem()) {
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                out.add((X509Certificate) c);
            }
        }
        return out;
    }

    private X509Certificate rootX1() throws Exception {
        return bundledRoots().get(0);
    }

    private X509TrustManager missingRootSystem() {
        return new X509TrustManager() {
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Simulated Android 6 missing root");
            }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("No client certificate trust");
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    @Test public void bundleContainsBothIsrgRootsWithVerifiedFingerprints() throws Exception {
        List<X509Certificate> roots = bundledRoots();
        assertEquals("X1 与 X2 都应打包", 2, roots.size());
        assertEquals(LegacyCertificateTrust.ROOT_X1_SHA256,
                LegacyCertificateTrust.fingerprintSha256(roots.get(0)));
        assertEquals(LegacyCertificateTrust.ROOT_X2_SHA256,
                LegacyCertificateTrust.fingerprintSha256(roots.get(1)));
        assertTrue(roots.get(0).getSubjectX500Principal().getName().contains("ISRG Root X1"));
        assertTrue(roots.get(1).getSubjectX500Principal().getName().contains("ISRG Root X2"));
    }

    @Test public void trustManagerLoadsFromAndroidResources() throws Exception {
        try (InputStream in = pem()) {
            X509TrustManager trust = LegacyCertificateTrust.fromRoots(in);
            assertTrue(trust.getAcceptedIssuers().length > 0);
        }
    }

    @Test public void missingSystemRootCanUseAdditionalRootButUnrelatedCertificatesAreRejected() throws Exception {
        X509Certificate x1 = rootX1();
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("root", x1);
        X509TrustManager trust = new LegacyCertificateTrust(missingRootSystem(),
                LegacyCertificateTrust.trustManager(store));
        trust.checkServerTrusted(new X509Certificate[]{x1}, "RSA");

        X509Certificate unrelated = null;
        for (X509Certificate certificate : LegacyCertificateTrust.trustManager(null).getAcceptedIssuers()) {
            if (!certificate.equals(x1)) { unrelated = certificate; break; }
        }
        assertNotNull("JVM has an unrelated public root", unrelated);
        try {
            trust.checkServerTrusted(new X509Certificate[]{unrelated}, "RSA");
            fail("An unrelated root must not be trusted by the ISRG-only fallback");
        } catch (CertificateException expected) { }
        try {
            trust.checkClientTrusted(new X509Certificate[]{x1}, "RSA");
            fail("Extra server root must not grant client trust");
        } catch (CertificateException expected) { }
    }

    @Test public void emptyChainIsRejected() throws Exception {
        try (InputStream in = pem()) {
            try {
                LegacyCertificateTrust.fromRoots(in).checkServerTrusted(new X509Certificate[0], "RSA");
                fail("Empty chain must fail");
            } catch (CertificateException expected) { }
        }
    }

    /** 任何不在白名单里的根证书都必须被拒绝，防止打包文件被替换。 */
    @Test public void substitutedRootIsRejected() throws Exception {
        X509Certificate x1 = rootX1();
        for (X509Certificate certificate : LegacyCertificateTrust.trustManager(null).getAcceptedIssuers()) {
            boolean isBundled = LegacyCertificateTrust.ROOT_X1_SHA256.equals(
                            LegacyCertificateTrust.fingerprintSha256(certificate))
                    || LegacyCertificateTrust.ROOT_X2_SHA256.equals(
                            LegacyCertificateTrust.fingerprintSha256(certificate));
            if (isBundled) continue;
            try {
                LegacyCertificateTrust.fromRoots(new ByteArrayInputStream(certificate.getEncoded()));
                fail("Only fingerprint-verified roots may be bundled: "
                        + certificate.getSubjectX500Principal().getName());
            } catch (CertificateException expected) { }
            return;
        }
        fail("Missing candidate root for the substitution test");
    }
}

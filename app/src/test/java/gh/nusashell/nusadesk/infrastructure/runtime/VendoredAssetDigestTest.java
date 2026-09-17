package gh.nusashell.nusadesk.infrastructure.runtime;

import android.content.res.AssetManager;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.VendoredFile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Asset packaging contract for the curated vendored payloads: every
 * {@link VendoredFile#getAssetPath()} the catalog pins must be readable
 * through the same {@link AssetManager#open(String)} call the installer
 * makes, and the streamed bytes must hash to the pinned SHA-256.
 *
 * <p>Robolectric serves the post-merge assets, so this exercises the defect
 * the device proved: AGP's asset merge gunzips any asset whose extension is
 * {@code gz} and strips the suffix ({@code udocker-1.3.17.tar.gz} reached
 * the APK as a decompressed {@code udocker-1.3.17.tar}), which both broke
 * the installer {@code open()} and fed the digest check different bytes.
 * Vendored payloads therefore never carry a {@code .gz} suffix — the
 * compose tarballs ship as {@code .tgz} and are installed under their
 * upstream {@code .tar.gz} names.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class VendoredAssetDigestTest {

    @Test
    public void everyVendoredAssetOpensAtItsPinnedPathAndDigest() throws Exception {
        AssetManager assets = RuntimeEnvironment.getApplication().getAssets();
        int count = 0;
        for (GuestAddonPayloadProfile profile : CuratedRuntimeCatalog.guestAddons()) {
            for (VendoredFile file : profile.getVendoredFiles()) {
                count++;
                assertEquals(
                        "asset bytes must match the catalog pin: " + file.getAssetPath(),
                        file.getSha256(), sha256(assets, file.getAssetPath()));
            }
        }
        assertTrue("the catalog must vendor files", count > 0);
    }

    @Test
    public void vendoredAssetsNeverUseAGzSuffix() {
        for (GuestAddonPayloadProfile profile : CuratedRuntimeCatalog.guestAddons()) {
            for (VendoredFile file : profile.getVendoredFiles()) {
                assertFalse(
                        "AGP ungzips and renames .gz assets at merge: "
                                + file.getAssetPath(),
                        file.getAssetPath().endsWith(".gz"));
            }
        }
    }

    @Test
    public void composeTarballAssetsKeepGzipBytes() throws Exception {
        // The pinned digests cover the gzip stream: each packaged tarball
        // must still start with the gzip magic, proving the merge did not
        // decompress the payload the guest re-verifies before extraction.
        AssetManager assets = RuntimeEnvironment.getApplication().getAssets();
        for (String asset : new String[]{
                "compose/udocker-1.3.17.tgz", "compose/PyYAML-6.0.1.tgz"}) {
            try (InputStream in = assets.open(asset)) {
                assertEquals(asset + " must be gzip data", 0x1f, in.read());
                assertEquals(asset + " must be gzip data", 0x8b, in.read());
            }
        }
    }

    private static String sha256(AssetManager assets, String assetPath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = assets.open(assetPath)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}

package tr.lidertv.nativeapp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetsTest {
    private fun a(n: String) = UpdateAssets.Asset(n, "https://exemple.invalid/$n")
    private val full = listOf(
        a("LiderTv-debug.apk"), a("LiderTv-debug.apk.sha256"), a("SHA256SUMS.txt"),
        a("LiderTv-1.1.1-armeabi-v7a.apk"), a("LiderTv-1.1.1-arm64-v8a.apk"), a("LiderTv-1.1.1-x86_64.apk"),
    )

    @Test fun chromecastHd_armeabiV7a_prendLApkV7a_verifieParSums() {
        val c = UpdateAssets.choose(full, "1.1.1", listOf("armeabi-v7a", "armeabi"))!!
        assertEquals("LiderTv-1.1.1-armeabi-v7a.apk", c.apkName)
        assertNull(c.sha256Url)
        assertEquals("https://exemple.invalid/SHA256SUMS.txt", c.sumsUrl)
    }

    @Test fun boxArm64_prendLApkArm64_premierAbiDisponible() {
        assertEquals("LiderTv-1.1.1-arm64-v8a.apk", UpdateAssets.choose(full, "1.1.1", listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!.apkName)
    }

    @Test fun copiesANomFixe_sansVersion_nePerturbentPasLeChoix() {
        val avecStables = full + listOf(a("LiderTv-arm64-v8a.apk"), a("LiderTv-arm64-v8a.apk.sha256"), a("LiderTv-armeabi-v7a.apk"), a("LiderTv.apk"))
        assertEquals("LiderTv-1.1.1-arm64-v8a.apk", UpdateAssets.choose(avecStables, "1.1.1", listOf("arm64-v8a", "armeabi-v7a"))!!.apkName)
        assertEquals("LiderTv-1.1.1-armeabi-v7a.apk", UpdateAssets.choose(avecStables, "1.1.1", listOf("armeabi-v7a"))!!.apkName)
    }

    @Test fun abiAbsentDeLaRelease_passeAuSuivant() {
        val sansArm64 = full.filterNot { it.name.contains("arm64") }
        assertEquals("LiderTv-1.1.1-armeabi-v7a.apk", UpdateAssets.choose(sansArm64, "1.1.1", listOf("arm64-v8a", "armeabi-v7a"))!!.apkName)
    }

    @Test fun rienNeCorrespond_repliSurLUniverselEtSonSha256() {
        val c = UpdateAssets.choose(full, "1.1.1", listOf("mips"))!!
        assertEquals("LiderTv-debug.apk", c.apkName)
        assertEquals("https://exemple.invalid/LiderTv-debug.apk.sha256", c.sha256Url)
        assertNull(c.sumsUrl)
    }

    @Test fun releaseAncienne_sansAssetsParAbi_universel() {
        val c = UpdateAssets.choose(listOf(a("LiderTv-debug.apk"), a("LiderTv-debug.apk.sha256")), "1.1.0", listOf("arm64-v8a"))!!
        assertEquals("LiderTv-debug.apk", c.apkName)
    }

    @Test fun sansSha256SumsLApkParAbiNEstPasChoisi() {
        val c = UpdateAssets.choose(full.filterNot { it.name == "SHA256SUMS.txt" }, "1.1.1", listOf("arm64-v8a"))!!
        assertEquals("LiderTv-debug.apk", c.apkName)
    }

    @Test fun aucunApk_null() = assertNull(UpdateAssets.choose(listOf(a("SHA256SUMS.txt")), "1.1.1", listOf("arm64-v8a")))

    @Test fun versionDuNomDAsset_doitCorrespondre() {
        assertEquals("LiderTv-debug.apk", UpdateAssets.choose(full, "1.2.0", listOf("arm64-v8a"))!!.apkName)
    }

    @Test fun digestFor_trouveLaBonneLigne() {
        val h1 = "a".repeat(64); val h2 = "b".repeat(64)
        val sums = "$h1  LiderTv-debug.apk\n$h2  LiderTv-1.1.1-arm64-v8a.apk\n"
        assertEquals(h2, UpdateAssets.digestFor(sums, "LiderTv-1.1.1-arm64-v8a.apk"))
        assertEquals(h1, UpdateAssets.digestFor(sums, "LiderTv-debug.apk"))
        assertEquals("", UpdateAssets.digestFor(sums, "inconnu.apk"))
        assertEquals(h2, UpdateAssets.digestFor("$h2 *LiderTv-x.apk", "LiderTv-x.apk"))
        assertEquals("", UpdateAssets.digestFor("zz  LiderTv-x.apk", "LiderTv-x.apk"))
    }

    @Test fun profilDm_choisiAvecLApkDuMemeAbi_absentSinon() {
        val withDm = full + a("LiderTv-1.1.1-armeabi-v7a.dm") + a("LiderTv-1.1.1-arm64-v8a.dm")
        val c = UpdateAssets.choose(withDm, "1.1.1", listOf("armeabi-v7a"))!!
        assertEquals("LiderTv-1.1.1-armeabi-v7a.dm", c.dmName)
        assertEquals("https://exemple.invalid/LiderTv-1.1.1-armeabi-v7a.dm", c.dmUrl)
        assertNull(UpdateAssets.choose(full, "1.1.1", listOf("armeabi-v7a"))!!.dmName)
        // APK universel (repli) : jamais de .dm.
        assertNull(UpdateAssets.choose(withDm, "1.1.1", listOf("mips"))!!.dmName)
    }
}

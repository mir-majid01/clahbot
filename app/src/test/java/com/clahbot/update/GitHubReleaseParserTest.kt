package com.clahbot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParserTest {

    private val releaseJson = """
        {
          "tag_name": "v6",
          "name": "AutoClash 2.4.0",
          "body": "Crash fixes and a smaller controller.",
          "html_url": "https://github.com/mir-majid01/clahbot/releases/tag/v6",
          "assets": [
            {
              "name": "AutoClash-2.4.0.apk",
              "browser_download_url": "https://example.test/AutoClash-2.4.0.apk",
              "size": 12345,
              "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            },
            {
              "name": "AutoClash-2.4.0.apk.sha256",
              "browser_download_url": "https://example.test/AutoClash-2.4.0.apk.sha256",
              "size": 80
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesReleaseAndSelectsTheApkAndChecksum() {
        val release = GitHubReleaseParser.parse(releaseJson)

        requireNotNull(release)
        assertEquals(6, release.versionCode)
        assertEquals("v6", release.tag)
        assertEquals("AutoClash 2.4.0", release.title)
        assertEquals("Crash fixes and a smaller controller.", release.notes)
        assertEquals("AutoClash-2.4.0.apk", release.apk.name)
        assertEquals(
            "https://example.test/AutoClash-2.4.0.apk",
            release.apk.url
        )
        assertEquals(12_345L, release.apk.size)
        assertEquals(
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            release.apk.digest
        )
        assertEquals(
            "https://example.test/AutoClash-2.4.0.apk.sha256",
            release.checksum?.url
        )
    }

    @Test
    fun aNewerVersionIsDetected() {
        val release = requireNotNull(GitHubReleaseParser.parse(releaseJson))

        assertTrue(release.isNewerThan(5))
        assertTrue(!release.isNewerThan(6))
        assertTrue(!release.isNewerThan(7))
    }

    @Test
    fun versionTagsAcceptPlainOrPrefixedCodes() {
        assertEquals(6, UpdateVersion.parseTag("v6"))
        assertEquals(6, UpdateVersion.parseTag("V6"))
        assertEquals(6, UpdateVersion.parseTag("6"))
        assertNull(UpdateVersion.parseTag("nightly"))
        assertNull(UpdateVersion.parseTag("v"))
        assertNull(UpdateVersion.parseTag("0"))
    }

    @Test
    fun aReleaseWithoutAnApkIsRejected() {
        val json = """
            {"tag_name":"v6","assets":[{"name":"notes.txt","browser_download_url":"https://example.test/notes.txt"}]}
        """.trimIndent()

        assertNull(GitHubReleaseParser.parse(json))
    }

    @Test
    fun malformedJsonIsRejected() {
        assertNull(GitHubReleaseParser.parse("{not json"))
    }
}

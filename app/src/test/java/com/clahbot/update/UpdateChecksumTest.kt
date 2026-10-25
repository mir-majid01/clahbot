package com.clahbot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateChecksumTest {

    private val sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    @Test
    fun readsTheShaFromAReleaseChecksumFile() {
        assertEquals(sha, UpdateChecksum.fromText("$sha  AutoClash-2.4.0.apk\n"))
    }

    @Test
    fun readsTheDigestField() {
        assertEquals(sha, UpdateChecksum.fromDigest("sha256:$sha"))
        assertEquals(sha, UpdateChecksum.fromDigest(sha.uppercase()))
    }

    @Test
    fun rejectsMissingOrMalformedChecksums() {
        assertNull(UpdateChecksum.fromText("not a hash"))
        assertNull(UpdateChecksum.fromDigest(null))
        assertNull(UpdateChecksum.fromDigest("sha256:1234"))
    }
}

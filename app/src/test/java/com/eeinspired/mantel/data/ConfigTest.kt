package com.eeinspired.mantel.data

import com.eeinspired.mantel.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Config.isAllowed] is the security boundary for the Remote Config
 * `server_base_url` override. Asserts against the build's own
 * `MANTEL_ALLOWED_HOST_SUFFIX` so it holds for any deployment config.
 */
class ConfigTest {

    private val suffix = BuildConfig.ALLOWED_HOST_SUFFIX

    @Test
    fun accepts_https_origin_on_the_allowlist() {
        assertTrue(Config.isAllowed("https://$suffix"))
        assertTrue(Config.isAllowed("https://sub.$suffix"))
        assertTrue(Config.isAllowed("https://a.b.$suffix"))
        assertTrue(Config.isAllowed(BuildConfig.BASE_URL))
    }

    @Test
    fun rejects_non_https() {
        assertFalse(Config.isAllowed("http://sub.$suffix"))
        assertFalse(Config.isAllowed("ftp://sub.$suffix"))
    }

    @Test
    fun rejects_hosts_off_the_allowlist() {
        assertFalse(Config.isAllowed("https://evil.com"))
        assertFalse(Config.isAllowed("https://evil-$suffix")) // no dot separator
        assertFalse(Config.isAllowed("https://$suffix.evil.com")) // suffix spoof
        assertFalse(Config.isAllowed("https://${suffix}x.evil.com"))
    }

    @Test
    fun rejects_anything_beyond_a_bare_origin() {
        assertFalse(Config.isAllowed("https://sub.$suffix/remote.php"))
        assertFalse(Config.isAllowed("https://sub.$suffix?x=1"))
        assertFalse(Config.isAllowed("https://user:pass@sub.$suffix"))
    }

    @Test
    fun rejects_malformed_and_empty() {
        assertFalse(Config.isAllowed(""))
        assertFalse(Config.isAllowed("not a url"))
        assertFalse(Config.isAllowed("https://"))
    }
}

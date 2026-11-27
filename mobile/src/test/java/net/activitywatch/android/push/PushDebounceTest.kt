/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package net.activitywatch.android.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushDebounceTest {

    private val gap = 60_000L

    @Test
    fun firstScreenOffAlwaysPushes() {
        assertTrue(PushDebounce.shouldPush(nowElapsed = 1_000L, lastAt = null, minGapMs = gap))
    }

    @Test
    fun repeatedScreenOffWithinAMinuteIsSkipped() {
        assertFalse(PushDebounce.shouldPush(nowElapsed = 59_999L, lastAt = 0L, minGapMs = gap))
        assertTrue(PushDebounce.shouldPush(nowElapsed = 60_000L, lastAt = 0L, minGapMs = gap))
    }

    @Test
    fun clockGoingBackwardsDoesNotBlockForever() {
        assertTrue(PushDebounce.shouldPush(nowElapsed = 10L, lastAt = 500_000L, minGapMs = gap))
    }
}

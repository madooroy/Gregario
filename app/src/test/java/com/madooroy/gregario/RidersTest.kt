package com.madooroy.gregario

import org.junit.Assert
import org.junit.Test

class RidersTest {
    @Test fun firstRiderKeepsTheOriginalKeysAndFolders() {
        Assert.assertEquals("icuApiKey", Riders.key("icuApiKey", Riders.FIRST))
        Assert.assertEquals("icuApiKey@r1a", Riders.key("icuApiKey", "r1a"))
        Assert.assertEquals("rides", Riders.dirName("rides", Riders.FIRST))
        Assert.assertEquals("rides-r1a", Riders.dirName("rides", "r1a"))
    }

    @Test fun listRoundTripsAndAlwaysStartsWithTheFirstRider() {
        val list = listOf(Rider("", "Alex"), Rider("rabc", "Jo\tJo"), Rider("rdef", "  Sam  "))
        val back = Riders.decode(Riders.encode(list), "Alex")
        Assert.assertEquals(listOf(Rider("", "Alex"), Rider("rabc", "Jo Jo"), Rider("rdef", "Sam")), back)
        Assert.assertEquals(listOf(Rider("", "Rider 1")), Riders.decode(null, ""))
    }

    @Test fun badLinesAndDuplicateIdsAreDropped() {
        val back = Riders.decode("rabc\tJo\nnot a line\n\tNoId\nr/x\tSlash\nrabc\tAgain", "Alex")
        Assert.assertEquals(listOf(Rider("", "Alex"), Rider("rabc", "Jo")), back)
    }

    @Test fun newIdsAreUniqueLettersAndDigits() {
        val a = Riders.newId(emptyList(), 1000)
        val b = Riders.newId(listOf(a), 1000)
        Assert.assertTrue(a != b)
        Assert.assertTrue((a + b).all { it.isLetterOrDigit() })
    }

    @Test fun askWhoIsRidingAfterEachRideAndAfterTwoHoursOnlyWithTwoOrMore() {
        val h = 60 * 60 * 1000L; val t = 100 * h
        Assert.assertFalse(Riders.needsConfirm(1, 0, 0, t))                    // one rider: never asked
        Assert.assertFalse(Riders.needsConfirm(1, 0, t - h, t))
        Assert.assertTrue(Riders.needsConfirm(2, 0, 0, t))                     // nobody has said yet
        Assert.assertFalse(Riders.needsConfirm(2, t - h, 0, t))                // said an hour ago, no ride since
        Assert.assertTrue(Riders.needsConfirm(2, t - h, t - 1, t))            // a ride finished since: ask again
        Assert.assertTrue(Riders.needsConfirm(2, t - 3 * h, 0, t))            // over two hours ago: ask again
    }
}

package com.example.mobilescanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsTest {
    private fun hit(s: String) = Terms.matchStrong(s)

    @Test fun distinctiveClientNamesAreMatchedInsideFileAndClassNames() {
        val names = listOf(
            "Wurst-Client-7.jar" to "wurst", "LiquidBounce-b99.zip" to "liquidbounce", "LiquidBounce+ 2.0" to "liquidbounce",
            "BleachHack.jar" to "bleachhack", "ThunderHack-1.7" to "thunderhack", "net/ThunderHack/Main.class" to "thunderhack",
            "Aristois.jar" to "aristois", "3arthh4ck.jar" to "3arthh4ck", "SalHack-1.0" to "salhack", "Nursultan Client" to "nursultan",
            "zamorozka.jar" to "zamorozka", "Melonity" to "melonity", "Wexside.jar" to "wexside", "Troxill" to "troxill",
            "CatLean-1.16" to "catlean", "WWE Client.jar" to "wwe client", "Wild Client.jar" to "wild client",
            "Excellent Client 1.2" to "excellent client", "Onix Client.mcpack" to "onix client", "Flarial.dll" to "flarial",
            "Ambrosial.mcpack" to "ambrosial", "Gamesense.jar" to "gamesense", "DoomsDay.jar" to "doomsday"
        )
        for ((s, key) in names) assertEquals("for $s", key, hit(s))
    }

    @Test fun shortAndWordLikeClientNamesMatchAsWholeWordsOnly() {
        for (s in listOf("Horion.mcpack", "latite-client.zip", "Cornos.jar", "Akrien", "Relake.jar", "NightX.jar", "Nitr0.jar", "Verist.jar")) {
            assertNotNull("should match $s", hit(s))
        }
        // not inside longer words
        for (s in listOf("horizon.jar", "latitem.jar", "cornostar", "phobosphere.png", "expraxis", "kiondra", "NightXP-pack", "horions")) {
            assertNull("must not match $s", hit(s))
        }
    }

    @Test fun ordinaryWordsAreOnlyFlaggedTogetherWithClient() {
        for (s in listOf("SigmaClient.jar", "Delta Client.jar", "venus-client-1.2", "EternityClient", "ExpensiveClient.jar", "Apollo Client", "Vector Client.jar", "RockstarClient", "PhobosClient.jar", "Kion Client", "Prax Client.mcpack", "Zephyr Client")) {
            assertNotNull("should match $s", hit(s))
        }
        for (s in listOf("Future.jar", "Impact.jar", "Sigma.jar", "Delta.jar", "Venus.png", "Vector.jar", "Expensive.txt", "Eternity.json", "Rockstar.jar", "Ares.jar", "Apollo.jar", "Celestial.jar", "Meteor.jar", "meteor.js", "Phobos.jar", "Inertia.jar", "Prax.mcpack", "Minced.jar", "Zephyr.jar", "Solstice.jar", "Kion.jar", "Wolfram.jar", "Deadcode.txt", "the future impact of delta vectors")) {
            assertNull("must not match $s", hit(s))
        }
    }

    @Test fun existingSignaturesStillWorkAndLegitModsAreNotFlagged() {
        assertEquals("wurst", hit("wurst"))
        assertEquals("vape", hit("Vape V4"))
        assertEquals("meteor", hit("meteor-client-0.5.jar"))
        assertEquals("impact", hit("ImpactClient"))
        assertEquals("future", hit("FutureClient"))
        for (s in listOf("sodium-fabric-0.5.jar", "OptiFine_1.20.jar", "iris-1.6.jar", "lithium.jar", "voxelmap.jar", "meteordevelopment.orbit", "Create-1.20.jar")) assertNull("must not match $s", hit(s))
    }

    @Test fun signatureCountGrew() {
        assertTrue(Terms.signatureCount > 60)
    }
}

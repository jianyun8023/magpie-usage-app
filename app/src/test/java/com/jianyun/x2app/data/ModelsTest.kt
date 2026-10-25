package com.jianyun.x2app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun deepseekBalanceWithoutKindIsStillABalanceCard() {
        val card = json.decodeFromString<QuotaCard>("""{
            "provider":"deepseek","windows":[],"balance":"¥5.29",
            "balanceSplit":[{"label":"Granted","text":"¥0.22"},{"label":"Topped up","text":"¥5.06"}]
        }""")
        assertEquals(true, card.isBalance)
        assertEquals("¥5.29", card.balance)
        assertEquals(2, card.balanceSplit.size)
        assertEquals(true, card.copy(balance = "¥0.00").isBalance)
        assertEquals(false, QuotaCard(provider = "subscription").isBalance)
        assertEquals(false, card.copy(kind = "plan").isBalance)
    }

    @Test fun legacyPayloadWithoutResetsDecodesWithNullResets() {
        val card = json.decodeFromString<QuotaCard>("""{"provider":"codex"}""")

        assertNull(card.resets)
    }

    @Test fun zeroAndUnknownCountsStayDistinctFromMissingCount() {
        val zero = json.decodeFromString<QuotaCard>("""{"provider":"codex","resets":{"count":0}}""")
        val unknown = json.decodeFromString<QuotaCard>("""{"provider":"codex","resets":{"until":"2030-01-01T00:00:00Z"}}""")

        assertEquals(0L, zero.resets?.count)
        assertNull(unknown.resets?.count)
    }

    @Test fun codexResetsDecodeCountUntilEachAndUnknownFields() {
        val card = json.decodeFromString<QuotaCard>("""{
            "provider":"codex",
            "resets":{
                "count":1,
                "until":"2030-01-02T03:04:05Z",
                "fiveHour":1,
                "weekly":0,
                "each":[{"until":"2030-01-02T03:04:05Z","window":"fiveHour"}],
                "futureField":true
            },
            "futureTopLevelField":true
        }""")

        assertEquals(QuotaResets(
            count = 1,
            until = "2030-01-02T03:04:05Z",
            fiveHour = 1,
            weekly = 0,
            each = listOf(QuotaResetTime(until = "2030-01-02T03:04:05Z", window = "fiveHour")),
        ), card.resets)
        assertEquals(false, card.resets?.byWindow)
    }

    @Test fun emptyEachWindowDecodesAsString() {
        val card = json.decodeFromString<QuotaCard>("""{
            "provider":"codex",
            "resets":{"count":2,"each":[{"until":"2030-01-03T00:00:00Z","window":""}]}
        }""")

        assertEquals(QuotaResets(
            count = 2,
            each = listOf(QuotaResetTime(until = "2030-01-03T00:00:00Z", window = "")),
        ), card.resets)
        assertEquals(false, card.resets?.byWindow)
    }

    @Test fun explicitNullResetsAndCountDecodeAsNull() {
        assertNull(json.decodeFromString<QuotaCard>("""{"provider":"codex","resets":null}""").resets)

        val card = json.decodeFromString<QuotaCard>("""{"provider":"codex","resets":{"count":null}}""")

        assertEquals(QuotaResets(), card.resets)
    }

    @Test fun byWindowWithZeroAndWeeklyCountsDecodes() {
        val card = json.decodeFromString<QuotaCard>("""{
            "provider":"codex",
            "resets":{
                "count":2,
                "byWindow":true,
                "fiveHour":0,
                "weekly":2,
                "each":[{"until":"2030-01-04T05:00:00Z","window":"weekly"}]
            }
        }""")

        assertEquals(QuotaResets(
            count = 2,
            fiveHour = 0,
            weekly = 2,
            each = listOf(QuotaResetTime(until = "2030-01-04T05:00:00Z", window = "weekly")),
            byWindow = true,
        ), card.resets)
    }
}

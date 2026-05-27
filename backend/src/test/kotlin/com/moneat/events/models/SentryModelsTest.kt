// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.events.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentryModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `transaction accepts numeric user id`() {
        val payload =
            """
            {
              "type": "transaction",
              "transaction": "/checkout",
              "start_timestamp": 1710000000.0,
              "timestamp": 1710000001.0,
              "user": { "id": 404, "email": "emma@harakirimail.com" }
            }
            """.trimIndent()

        val transaction = json.decodeFromString<SentryTransaction>(payload)

        assertEquals("404", transaction.user?.id)
        assertEquals("emma@harakirimail.com", transaction.user?.email)
    }

    @Test
    fun `transaction keeps string user id`() {
        val payload =
            """
            {
              "type": "transaction",
              "transaction": "/checkout",
              "user": { "id": "404" }
            }
            """.trimIndent()

        val transaction = json.decodeFromString<SentryTransaction>(payload)

        assertEquals("404", transaction.user?.id)
    }

    @Test
    fun `transaction accepts null user id`() {
        val payload =
            """
            {
              "type": "transaction",
              "transaction": "/checkout",
              "user": { "id": null, "email": "emma@harakirimail.com" }
            }
            """.trimIndent()

        val transaction = json.decodeFromString<SentryTransaction>(payload)

        assertNull(transaction.user?.id)
        assertEquals("emma@harakirimail.com", transaction.user?.email)
    }
}

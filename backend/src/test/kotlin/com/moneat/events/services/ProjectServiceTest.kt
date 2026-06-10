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

package com.moneat.events.services

import com.moneat.events.repositories.ProjectRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectServiceTest {

    private val sentryCompatibleKeyRegex = Regex("^[a-f0-9]{32}$")

    @Test
    fun `addProjectTarget generates Sentry-compatible hex keys without URL-hostile characters`() {
        val publicKeySlot = slot<String>()
        val secretKeySlot = slot<String>()
        val mockRepo = mockk<ProjectRepository>()
        every { mockRepo.findProjectKeyByTarget(1L, "web") } returns false
        every {
            mockRepo.createProjectKey(1L, capture(publicKeySlot), capture(secretKeySlot), "web")
        } just runs

        val service = ProjectService(mockRepo, mockk(relaxed = true))
        val response = service.addProjectTarget(1L, "web")

        assertTrue(sentryCompatibleKeyRegex.matches(publicKeySlot.captured))
        assertTrue(sentryCompatibleKeyRegex.matches(secretKeySlot.captured))
        assertTrue(response.dsn.contains(publicKeySlot.captured))
        assertEquals("web", response.platformTarget)
    }
}

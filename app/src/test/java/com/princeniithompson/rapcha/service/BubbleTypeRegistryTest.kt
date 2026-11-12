package com.princeniithompson.rapcha.service

import com.princeniithompson.rapcha.service.floating.BubbleType
import com.princeniithompson.rapcha.service.floating.BubbleTypeRegistry
import com.princeniithompson.rapcha.service.floating.LiveOverlayType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BubbleTypeRegistryTest {

    @Test
    fun testDefaultBubblesRegistered() {
        val types = BubbleTypeRegistry.getAll()
        assertEquals(2, types.size)
        assertEquals("words", types[0].id)
        assertEquals("mini", types[1].id)
    }

    @Test
    fun testFallbackToWordsForUnknownId() {
        val unknown = BubbleTypeRegistry.get("unknown_bubble_123")
        assertEquals("words", unknown.id)
    }

    @Test
    fun testDynamicThirdBubbleRegistrationAndRemoval() {
        val dummyId = "dummy_test_bubble"
        val dummyType = BubbleType(
            id = dummyId,
            displayName = "Dummy Bubble",
            previewComposable = { _, _, _ -> },
            liveOverlayType = LiveOverlayType.MINI
        )

        // 1. Register third bubble
        BubbleTypeRegistry.register(dummyType)
        val allAfterRegister = BubbleTypeRegistry.getAll()
        assertEquals(3, allAfterRegister.size)
        val retrieved = BubbleTypeRegistry.get(dummyId)
        assertNotNull(retrieved)
        assertEquals("Dummy Bubble", retrieved.displayName)

        // 2. Unregister dummy bubble
        BubbleTypeRegistry.unregister(dummyId)
        val allAfterUnregister = BubbleTypeRegistry.getAll()
        assertEquals(2, allAfterUnregister.size)
        assertEquals("words", BubbleTypeRegistry.get(dummyId).id)
    }
}

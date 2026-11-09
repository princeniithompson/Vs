package com.princeniithompson.rapcha.service.floating

import com.princeniithompson.rapcha.config.RapchaConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChefLyraPlacementTest {

    @Test
    fun `live setup system text is chef only and lyra is only on the user turn`() {
        val chef = PolishPromptBuilder.CHEF_GOLDEN_RULES
        val beside = LyraPolishInstruction.instructionsBesideChef("Claude")
        val rendered = LyraPolishInstruction.forKeyboard("Claude")
        assertTrue(rendered.contains(beside))
        assertTrue(beside.startsWith("KEYBOARD POLISH OVERRIDE:"))
        val lyra = LyraPolishInstruction.methodologyText("the AI app")
        assertTrue(beside.contains(LyraPolishInstruction.methodologyText("Claude")))
        assertFalse(chef.contains("Lyra"))

        val system = Stage2LivePrompt.systemInstruction(Stage2Category.AI, "Claude", listOf("Niithompson"))
        assertEquals(chef, system)
        val setup = JSONObject(LivePolishProtocol.setupMessage(system))
        val setupObj = setup.getJSONObject("setup")
        assertEquals(RapchaConfig.STAGE2_LIVE_MODEL, setupObj.getString("model"))
        assertEquals(
            "AUDIO",
            setupObj.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0)
        )
        val systemText = setupObj.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals(chef, systemText)
        assertFalse(setup.toString().contains("Lyra"))
        assertFalse(setup.toString().contains("KEYBOARD POLISH OVERRIDE"))
        assertFalse(setup.toString().contains("Niithompson"))

        val user = Stage2LivePrompt.userTurn(
            Stage2Category.AI,
            "seven crates actually three",
            listOf("Niithompson"),
            appName = "Claude"
        )
        val wire = JSONObject(LivePolishProtocol.clientContentMessage(user))
        val turn = wire.getJSONObject("clientContent").getJSONArray("turns").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        val task = turn.getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(task.contains(LyraPolishInstruction.methodologyText("Claude")))
        assertTrue(task.contains("KEYBOARD POLISH OVERRIDE"))
        assertTrue(task.contains("Niithompson"))
        assertTrue(task.contains("seven crates actually three"))
        assertFalse(task.startsWith(chef))
        assertTrue(lyra.startsWith("You are Lyra"))

        File("/opt/cursor/artifacts").mkdirs()
        File("/opt/cursor/artifacts/Chef.txt").writeText(chef, Charsets.UTF_8)
        File("/opt/cursor/artifacts/Lyra.txt").writeText(lyra, Charsets.UTF_8)
    }
}

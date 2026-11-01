package dev.omalauncher.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLinkTest {
    private val written = ArrayList<JSONObject>()
    private val write: (String) -> Unit = { written += JSONObject(it) }

    @Test fun claudeStreamsATurn() {
        val link = ClaudeLink(write)
        link.open(null)
        assertEquals("initialize", written.single().getJSONObject("request").getString("subtype"))
        link.prompt("hi")
        assertEquals("hi", written.last().getJSONObject("message").getString("content"))
        val events =
            listOf(
                """{"type":"system","subtype":"init","session_id":"s1"}""",
                """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Two plus"}},"session_id":"s1","parent_tool_use_id":null}""",
                """{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","name":"Read"}},"session_id":"s1","parent_tool_use_id":null}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/root/.config/omalauncher/widgets/.preview/a.owl.png"}}]},"session_id":"s1","parent_tool_use_id":null}""",
                """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"nope"}},"session_id":"s1","parent_tool_use_id":"t9"}""",
                """{"type":"result","subtype":"success","is_error":false,"session_id":"s1"}""",
            ).flatMap(link::read)
        assertEquals(
            listOf(
                LinkEvent.Session("s1"),
                LinkEvent.Ready,
                LinkEvent.Text("Two plus"),
                LinkEvent.Working,
                LinkEvent.Step(Act(Act.Verb.Read, "a.owl.png"), listOf("/root/.config/omalauncher/widgets/.preview/a.owl.png")),
                LinkEvent.Done(),
            ),
            events,
        )
        assertTrue(link.command("s1").endsWith("--resume 's1'"))
    }

    @Test fun claudeAsksAndIsAnswered() {
        val link = ClaudeLink(write)
        val ask =
            link.read(
                """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"cd w && rm -rf build","description":"Clean"}}}""",
            ).single() as LinkEvent.Ask
        assertEquals(LinkEvent.Ask("r1", Act(Act.Verb.Run, "Clean"), "cd w && rm -rf build", "Bash:cd rm"), ask)
        assertTrue(link.answer("r1", false, false, "keep it"))
        val resp = written.last().getJSONObject("response")
        assertEquals("r1", resp.getString("request_id"))
        assertEquals("deny", resp.getJSONObject("response").getString("behavior"))
        assertTrue(resp.getJSONObject("response").getString("message").endsWith("keep it"))
        link.read("""{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Read","input":{"file_path":"/a"}}}""")
        link.answer("r2", true, false, null)
        val allow = written.last().getJSONObject("response").getJSONObject("response")
        assertEquals("/a", allow.getJSONObject("updatedInput").getString("file_path"))
    }

    @Test fun claudeFailedTurn() {
        val link = ClaudeLink(write)
        assertEquals(
            listOf(LinkEvent.Done(error = "Not logged in · Please run /login")),
            link.read("""{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login"}"""),
        )
    }

    @Test fun acpOpensPromptsAndAsks() {
        val link = AcpLink(write, "grok agent stdio")
        link.open(null)
        assertEquals("initialize", written.last().getString("method"))
        // A prompt before it's ready waits.
        link.prompt("build it")
        assertEquals(1, written.size)
        assertTrue(link.read("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""").isEmpty())
        assertEquals("session/new", written.last().getString("method"))
        assertEquals(listOf(LinkEvent.Session("g1"), LinkEvent.Ready), link.read("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"g1"}}"""))
        val prompt = written.last()
        assertEquals("session/prompt", prompt.getString("method"))
        val text = prompt.getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text")
        assertTrue("the first prompt says how to talk", text.startsWith("(You are talking") && text.endsWith("build it"))
        // A second prompt waits for the first.
        link.prompt("and again")
        assertEquals(prompt.toString(), written.last().toString())

        assertEquals(
            listOf(LinkEvent.Text("On it.")),
            link.read("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"g1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"On it."}}}}"""),
        )
        assertEquals(
            listOf(LinkEvent.Working, LinkEvent.Step(Act(Act.Verb.Read, "p.png"), listOf("/root/p.png"))),
            link.read("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"g1","update":{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read p.png","kind":"read","locations":[{"path":"/root/p.png"}]}}}"""),
        )
        val ask =
            link.read(
                """{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"g1","toolCall":{"title":"rm","kind":"execute","rawInput":{"command":"rm -rf b"}},"options":[{"optionId":"a1","kind":"allow_once"},{"optionId":"a2","kind":"allow_always"},{"optionId":"r1","kind":"reject_once"}]}}""",
            ).single() as LinkEvent.Ask
        assertEquals(LinkEvent.Ask("p7", Act(Act.Verb.Run, "rm"), "rm -rf b", "Bash:rm"), ask)
        assertFalse("ACP can't take words with a refusal", link.answer("p7", true, true, null))
        assertEquals(7, written.last().getInt("id"))
        assertEquals("a2", written.last().getJSONObject("result").getJSONObject("outcome").getString("optionId"))

        // Fs requests are refused.
        link.read("""{"jsonrpc":"2.0","id":8,"method":"fs/read_text_file","params":{}}""")
        assertEquals(-32601, written.last().getJSONObject("error").getInt("code"))

        assertEquals(listOf(LinkEvent.Done()), link.read("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}"""))
        // The waiting prompt goes now, without the voice instructions again.
        assertEquals("and again", written.last().getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text"))
    }

    @Test fun acpResumesOrStartsOver() {
        val link = AcpLink(write, "grok agent stdio")
        link.open("old")
        link.read("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        assertEquals("session/load", written.last().getString("method"))
        // The replay of the old conversation isn't shown.
        assertTrue(
            link.read("""{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"old"}}}}""").isEmpty(),
        )
        assertEquals(listOf(LinkEvent.ResumeFailed), link.read("""{"jsonrpc":"2.0","id":2,"error":{"code":-32002,"message":"not found"}}"""))
        assertEquals("session/new", written.last().getString("method"))
    }

    @Test fun alwaysCoversEachProgram() {
        val always = setOf(Steps.alwaysKey("Bash", "cd ~/w && curl -s x | head -c 3; echo"))
        assertEquals(setOf("cd", "curl", "head", "echo"), Steps.programs("cd ~/w && curl -s x | head -c 3; echo"))
        assertTrue(Steps.covered(Steps.alwaysKey("Bash", "curl y | head"), always))
        assertFalse(Steps.covered(Steps.alwaysKey("Bash", "cd w && rm -rf build"), always))
        assertFalse(Steps.covered(Steps.alwaysKey("Bash", "FOO=1 cd x; echo $(whoami)"), always))
    }
}

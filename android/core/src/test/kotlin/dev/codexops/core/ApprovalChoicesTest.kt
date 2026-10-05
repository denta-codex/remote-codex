package dev.codexops.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalChoicesTest {
    private fun command(values: JsonElement? = null, extra: JsonObject = obj()) = Decision(s("request"),
        "item/commandExecution/requestApproval", JsonObject(extra + (values?.let { mapOf("availableDecisions" to it) } ?: emptyMap())), 3)
    private val exec = obj("acceptWithExecpolicyAmendment" to obj("execpolicy_amendment" to JsonArray(listOf(s("git"), s("status")))))
    private fun network(action: String) = obj("applyNetworkPolicyAmendment" to obj("network_policy_amendment" to obj(
        "host" to s("example.test"), "action" to s(action))))

    @Test fun advertisedDecisionsPreserveOrderAndExactWireValues() {
        val values = listOf(s("decline"), network("deny"), exec, s("acceptForSession"), network("allow"), s("cancel"), s("accept"))
        val request = command(JsonArray(values))
        val choices = ApprovalChoices.choices(request)
        assertEquals(values, choices.map { it.value })
        choices.forEach { assertTrue(ApprovalChoices.validResult(request, it.result)) }
        assertEquals(obj("decision" to exec), choices[2].result)
        assertTrue(choices[1].consequence.contains("example.test"))
        assertFalse(choices[1].grantsAccess)
        assertFalse(choices[5].grantsAccess)
        assertTrue(choices[2].consequence.contains("[\"git\",\"status\"]"))
        assertTrue(choices[3].consequence.contains("session"))
    }

    @Test fun restrictionsAndUnknownVariantsNeverAddChoices() {
        val request = command(JsonArray(listOf(s("cancel"), s("futureChoice"), network("unknown"), obj("acceptWithExecpolicyAmendment" to obj()))))
        assertEquals(listOf(s("cancel")), ApprovalChoices.choices(request).map { it.value })
        assertFalse(ApprovalChoices.validResult(request, obj("decision" to s("accept"))))
        assertTrue(ApprovalChoices.choices(command(JsonArray(emptyList()))).isEmpty())
        assertTrue(ApprovalChoices.choices(command(s("accept"))).isEmpty())
    }

    @Test fun absentListsKeepBasicCommandChoicesAndStdinUsesSameContract() {
        listOf(command(), command(JsonNull), command(extra = obj("kind" to s("writeStdin"), "approvalId" to s("callback")))).forEach {
            assertEquals(listOf(s("accept"), s("decline")), ApprovalChoices.choices(it).map { choice -> choice.value })
        }
        val request = command(JsonArray(listOf(exec)), obj("kind" to s("writeStdin")))
        assertEquals(exec, ApprovalChoices.choices(request).single().value)
    }

    @Test fun fileChangesUseTheirOwnDecisionEnum() {
        val request = command().copy(method = "item/fileChange/requestApproval")
        assertEquals(listOf(s("accept"), s("acceptForSession"), s("decline"), s("cancel")), ApprovalChoices.choices(request).map { it.value })
        assertFalse(ApprovalChoices.validResult(request, obj("decision" to exec)))
        assertTrue(ApprovalChoices.choices(request)[1].consequence.contains("same files"))
    }

    private fun entry(access: String, path: String) = obj("access" to s(access), "path" to obj("type" to s("path"), "path" to s(path)))
    private val read = entry("read", "/fixture/read")
    private val write = entry("write", "/fixture/write")
    private val deny = entry("deny", "/fixture/write/private")
    private val permissions = obj("network" to obj("enabled" to JsonPrimitive(true)), "fileSystem" to obj(
        "entries" to JsonArray(listOf(read, write, deny)), "read" to JsonArray(listOf(s("/legacy/read"))),
        "globScanMaxDepth" to JsonPrimitive(4)))
    private fun permissionRequest(profile: JsonObject = permissions) = Decision(s("request"), "item/permissions/requestApproval", obj("permissions" to profile), 3)

    @Test fun permissionSubsetsCopyEntriesAndKeepRestrictions() {
        val request = permissionRequest()
        val selection = PermissionSelection(request)
        assertEquals(setOf("network", "entries/0", "entries/1", "read/0"), selection.options.map { it.id }.toSet())
        val result = selection.result(setOf("entries/1"))
        assertEquals("turn", result.str("scope"))
        assertEquals(obj("fileSystem" to obj("entries" to JsonArray(listOf(write, deny)), "globScanMaxDepth" to JsonPrimitive(4))), result.map("permissions"))
        assertTrue(ApprovalChoices.validResult(request, result))
        assertEquals(permissions, selection.result(selection.options.map { it.id }.toSet()).map("permissions"))
        assertEquals("session", selection.result(setOf("network"), "session").str("scope"))
        assertEquals(obj("permissions" to obj(), "scope" to s("turn")), selection.result(emptySet(), "session"))
    }

    @Test fun unrequestedPermissionsAndBroadenedGrantsAreRejected() {
        val request = permissionRequest()
        val selection = PermissionSelection(request)
        val broadened = obj("permissions" to obj("fileSystem" to obj("entries" to JsonArray(listOf(write)), "globScanMaxDepth" to JsonPrimitive(4))), "scope" to s("turn"))
        assertFalse(ApprovalChoices.validResult(request, broadened))
        val invented = obj("permissions" to obj("fileSystem" to obj("write" to JsonArray(listOf(s("/"))))), "scope" to s("session"))
        assertFalse(ApprovalChoices.validResult(request, invented))
        assertFalse(selection.validResult(obj("permissions" to permissions, "scope" to s("forever"))))
        assertThrows(IllegalArgumentException::class.java) { selection.result(setOf("invented")) }
        assertThrows(IllegalArgumentException::class.java) { selection.result(emptySet(), "forever") }
    }

    @Test fun unknownFilesystemRestrictionsCannotBeSilentlyDropped() {
        val fs = JsonObject(permissions.map("fileSystem") + ("futureRestriction" to s("required")))
        val selection = PermissionSelection(permissionRequest(obj("fileSystem" to fs)))
        assertTrue(selection.options.isEmpty())
        assertEquals(obj(), selection.result(emptySet()).map("permissions"))
    }

    @Test fun duplicateRequestedPathsCanBeSelectedIndividually() {
        val request = permissionRequest(obj("fileSystem" to obj("entries" to JsonArray(listOf(write, write, deny)))))
        val selection = PermissionSelection(request)
        assertTrue(selection.validResult(selection.result(setOf("entries/1"))))
        assertTrue(selection.validResult(selection.result(setOf("entries/0", "entries/1"))))
        val interleaved = PermissionSelection(permissionRequest(obj("fileSystem" to obj("entries" to JsonArray(listOf(write, read, write, deny))))))
        assertTrue(interleaved.validResult(interleaved.result(setOf("entries/1", "entries/2"))))
    }

    @Test fun emptyAdvertisedCommandPrefixStillRequiresExplicitConfirmation() {
        val amendment = obj("acceptWithExecpolicyAmendment" to obj("execpolicy_amendment" to JsonArray(emptyList())))
        val choice = ApprovalChoices.choices(command(JsonArray(listOf(amendment)))).single()
        assertEquals(amendment, choice.value)
        assertTrue(choice.consequence.contains("match broadly"))
    }
}

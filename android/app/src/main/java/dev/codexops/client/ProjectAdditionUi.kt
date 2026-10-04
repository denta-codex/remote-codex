package dev.codexops.client

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectAdditionSheet(screen: ScreenState, actions: ConversationActions) {
    val state = screen.projectAddition
    if (!state.visible) return
    val enabled = screen.ready && !state.working && !state.loading
    ModalBottomSheet(onDismissRequest = actions::dismissAddProject,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .imePadding().padding(horizontal = 16.dp).testTag("add-project-sheet")) {
            Text("Add project · ${screen.host.displayName}", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!screen.ready) Text("Disconnected. Reconnect to browse or add a project.")
                if (state.loading || state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("add-project-error")) }
                if (state.pending != null) {
                    Text(state.path)
                    TextButton(actions::checkProjectRegistration, enabled = enabled,
                        modifier = Modifier.testTag("check-project-registration")) { Text("Check again") }
                } else if (state.confirming) {
                    Text(state.loadedPath.orEmpty())
                    OutlinedTextField(state.name, actions::projectName, label = { Text("Project name") },
                        singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("project-name"))
                    Text("An existing project for this folder will be reused.")
                    if (state.matches.isEmpty()) Button(actions::addProject, enabled = enabled && state.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("confirm-add-project")) { Text("Add project") }
                    TextButton({ actions.browseProjectFolder(state.path) }, enabled = enabled) { Text("Choose another folder") }
                } else {
                    OutlinedTextField(state.path, actions::projectPath, label = { Text("Folder path on ${screen.host.displayName}") },
                        singleLine = true, enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("project-folder-path"))
                    Row {
                        TextButton({ actions.browseProjectFolder(state.path) }, enabled = screen.ready && !state.working,
                            modifier = Modifier.testTag("open-project-folder")) { Text("Open path") }
                        TextButton({ actions.browseProjectFolder(state.loadedPath!!.substringBeforeLast('/').ifEmpty { "/" }) },
                            enabled = enabled && state.loadedPath != null && state.loadedPath != "/",
                            modifier = Modifier.testTag("project-folder-up")) { Text("Up") }
                    }
                    if (state.loadedPath != null && state.folders.isEmpty()) Text("No subfolders. You can use this folder.")
                    state.folders.forEach { name ->
                        TextButton({ actions.browseProjectFolder("${state.loadedPath!!.trimEnd('/')}/$name") },
                            enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("project-folder-$name")) {
                            Glyph(R.drawable.ic_folder)
                            Spacer(Modifier.width(8.dp))
                            Text(name, Modifier.weight(1f))
                        }
                    }
                }
                state.matches.forEach { project ->
                    OutlinedButton({ actions.chooseMatchingProject(project.id) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("matching-project-${project.id}")) {
                        Column { Text(project.name); Text(project.roots.joinToString("\n")) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(actions::dismissAddProject) { Text("Close") }
                if (!state.confirming && state.pending == null) Button(actions::useProjectFolder,
                    enabled = enabled && state.loadedPath != null, modifier = Modifier.testTag("use-project-folder")) { Text("Use this folder") }
            }
        }
    }
}

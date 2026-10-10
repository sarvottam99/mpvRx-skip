/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.storage.RecursiveRarExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
object RecursiveRarExtractorScreen : Screen {

  @androidx.compose.material3.ExperimentalMaterial3Api
  @Composable
  override fun Content() {

    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    var selectedFolderUri by rememberSaveable {
      mutableStateOf("")
    }

    var selectedFolderName by rememberSaveable {
      mutableStateOf("")
    }

    var isRunning by remember {
      mutableStateOf(false)
    }

    var progressMessage by remember {
      mutableStateOf("")
    }

    var errorMessage by remember {
      mutableStateOf("")
    }

    var summary by remember {
      mutableStateOf<RecursiveRarExtractor.Summary?>(null)
    }

    val folderPicker =
      rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
      ) { uri: Uri? ->

        if (uri == null) {
          return@rememberLauncherForActivityResult
        }

        try {

          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
              Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )

          selectedFolderUri = uri.toString()

          selectedFolderName =
            DocumentFile
              .fromTreeUri(context, uri)
              ?.name
              ?: uri.lastPathSegment.orEmpty()

          progressMessage = ""
          errorMessage = ""
          summary = null

        } catch (error: Exception) {

          errorMessage =
            context.getString(
              R.string.rar_extractor_folder_access_failed,
              error.localizedMessage
                ?: context.getString(
                  R.string.generic_unknown_error,
                ),
            )
        }
      }

    Scaffold(
      topBar = {

        TopAppBar(
          title = {

            Text(
              text =
                stringResource(
                  R.string.pref_rar_extractor_title,
                ),
              style =
                MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },

          navigationIcon = {

            if (LocalShowSettingsBackArrow.current) {

              androidx.compose.material3.IconButton(
                onClick = {
                  backStack.popSafely()
                },
              ) {

                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          },
        )
      },
    ) { padding ->

      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(
              rememberScrollState(),
            )
            .padding(16.dp)
            .settingsSearchTarget(
              R.string.pref_rar_extractor_title,
            ),

        verticalArrangement =
          Arrangement.spacedBy(12.dp),
      ) {

        Text(
          text =
            stringResource(
              R.string.pref_rar_extractor_summary,
            ),
          style =
            MaterialTheme.typography.bodyMedium,
          color =
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
          modifier =
            Modifier.fillMaxWidth(),

          colors =
            CardDefaults.cardColors(
              containerColor =
                MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {

          Column(
            modifier =
              Modifier.padding(16.dp),

            verticalArrangement =
              Arrangement.spacedBy(6.dp),
          ) {

            Text(
              text =
                stringResource(
                  R.string.rar_extractor_folder_label,
                ),
              style =
                MaterialTheme.typography.titleSmall,
              fontWeight =
                FontWeight.SemiBold,
            )

            Text(
              text =
                selectedFolderName.ifBlank {
                  stringResource(
                    R.string.rar_extractor_folder_not_selected,
                  )
                },

              style =
                MaterialTheme.typography.bodyMedium,

              color =
                if (selectedFolderUri.isBlank()) {
                  MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
            )
          }
        }

        OutlinedButton(
          onClick = {
            folderPicker.launch(null)
          },

          enabled = !isRunning,

          modifier =
            Modifier.fillMaxWidth(),
        ) {

          Icon(
            Icons.RoundedFilled.Folder,
            contentDescription = null,
          )

          Spacer(
            modifier =
              Modifier.size(8.dp),
          )

          Text(
            stringResource(
              R.string.rar_extractor_select_folder,
            ),
          )
        }

        Button(
          onClick = {

            val treeUri =
              Uri.parse(
                selectedFolderUri,
              )

            scope.launch {

              isRunning = true
              progressMessage =
                context.getString(
                  R.string.rar_extractor_searching,
                )

              errorMessage = ""
              summary = null

              try {

                summary =
                  RecursiveRarExtractor.extract(
                    context = context.applicationContext,
                    rootUri = treeUri,
                  ) { message ->

                    withContext(
                      Dispatchers.Main.immediate,
                    ) {
                      progressMessage = message
                    }
                  }

                progressMessage =
                  context.getString(
                    R.string.rar_extractor_complete,
                  )

              } catch (
                cancelled: CancellationException,
              ) {

                throw cancelled

              } catch (
                error: Exception,
              ) {

                errorMessage =
                  context.getString(
                    R.string.rar_extractor_run_failed,
                    error.localizedMessage
                      ?: context.getString(
                        R.string.generic_unknown_error,
                      ),
                  )

              } finally {

                isRunning = false
              }
            }
          },

          enabled =
            selectedFolderUri.isNotBlank() &&
              !isRunning,

          modifier =
            Modifier.fillMaxWidth(),
        ) {

          Text(
            stringResource(
              R.string.rar_extractor_extract,
            ),
          )
        }

        if (isRunning) {

          LinearProgressIndicator(
            modifier =
              Modifier.fillMaxWidth(),
          )

          Text(
            text = progressMessage,
            style =
              MaterialTheme.typography.bodyMedium,
          )
        }

        if (errorMessage.isNotBlank()) {

          Card(
            modifier =
              Modifier.fillMaxWidth(),

            colors =
              CardDefaults.cardColors(
                containerColor =
                  MaterialTheme.colorScheme.errorContainer,
              ),
          ) {

            Text(
              text = errorMessage,

              modifier =
                Modifier.padding(16.dp),

              style =
                MaterialTheme.typography.bodyMedium,

              color =
                MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }

        summary?.let { result ->

          Card(
            modifier =
              Modifier.fillMaxWidth(),

            colors =
              CardDefaults.cardColors(
                containerColor =
                  MaterialTheme.colorScheme.surfaceContainerLow,
              ),
          ) {

            Column(
              modifier =
                Modifier.padding(16.dp),

              verticalArrangement =
                Arrangement.spacedBy(8.dp),
            ) {

              Text(
                text =
                  stringResource(
                    R.string.rar_extractor_results_title,
                  ),

                style =
                  MaterialTheme.typography.titleMedium,

                fontWeight =
                  FontWeight.Bold,
              )

              if (result.archivesFound == 0) {

                Text(
                  stringResource(
                    R.string.rar_extractor_no_archives,
                  ),
                )

              } else {

                Text(
                  stringResource(
                    R.string.rar_extractor_results,

                    result.archivesFound,
                    result.successful,
                    result.failed,
                    result.filesExtracted,
                  ),

                  style =
                    MaterialTheme.typography.bodyMedium,
                )
              }

              if (result.warnings.isNotEmpty()) {

                HorizontalDivider()

                Text(
                  text =
                    stringResource(
                      R.string.rar_extractor_warnings_title,
                    ),

                  style =
                    MaterialTheme.typography.titleSmall,

                  fontWeight =
                    FontWeight.SemiBold,
                )

                result.warnings.forEach { warning ->

                  Text(
                    text = warning,

                    style =
                      MaterialTheme.typography.bodySmall,

                    color =
                      MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              if (result.errors.isNotEmpty()) {

                HorizontalDivider()

                Text(
                  text =
                    stringResource(
                      R.string.rar_extractor_errors_title,
                    ),

                  style =
                    MaterialTheme.typography.titleSmall,

                  fontWeight =
                    FontWeight.SemiBold,

                  color =
                    MaterialTheme.colorScheme.error,
                )

                result.errors.forEach { failure ->

                  Text(
                    text = failure,

                    style =
                      MaterialTheme.typography.bodySmall,

                    color =
                      MaterialTheme.colorScheme.error,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

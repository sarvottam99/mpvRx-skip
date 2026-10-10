/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale

object RecursiveRarExtractor {

  data class Summary(
    val archivesFound: Int,
    val successful: Int,
    val failed: Int,
    val filesExtracted: Int,
    val warnings: List<String>,
    val errors: List<String>,
  )

  private data class ScannedFile(
    val document: DocumentFile,
    val relativePath: String,
  ) {
    val name: String
      get() = relativePath.substringAfterLast('/')

    val parentPath: String
      get() = relativePath.substringBeforeLast('/', "")
  }

  private data class Volume(
    val document: DocumentFile,
    val number: Int,
  )

  private data class PreparedInput(
    val singleDocument: DocumentFile? = null,
    val volumePaths: List<String> = emptyList(),
    val temporaryFiles: List<File> = emptyList(),
  ) {
    val isVolumeSet: Boolean
      get() = volumePaths.size > 1

    fun cleanup() {
      temporaryFiles.forEach { file ->
        runCatching { file.delete() }
      }
    }
  }

  private val partPattern =
    Regex("""^(.*)\.part(\d{1,3})\.rar$""", RegexOption.IGNORE_CASE)

  private val legacyVolumePattern =
    Regex("""^(.+)\.r(\d{2,3})$""", RegexOption.IGNORE_CASE)

  private const val MAX_ARCHIVE_ENTRIES = 100_000
  private const val IO_BUFFER_SIZE = 64 * 1024

  suspend fun extract(
    context: Context,
    rootUri: Uri,
    onProgress: suspend (String) -> Unit = {},
  ): Summary =
    withContext(Dispatchers.IO) {

      val root =
        DocumentFile.fromTreeUri(context, rootUri)
          ?: throw IOException("The selected folder is no longer accessible.")

      require(root.isDirectory) {
        "The selected location is not a folder."
      }

      onProgress("Searching for RAR archives…")

      val allFiles = scanTree(root)

      /*
       * Only process:
       *
       *   file.rar
       *   file.part1.rar
       *   file.part01.rar
       *   file.part001.rar
       *
       * Other multipart volumes are discovered automatically and are NOT
       * processed separately.
       */
      val archives =
        allFiles
          .filter { file ->
            if (!file.name.endsWith(".rar", ignoreCase = true)) {
              false
            } else {
              val match = partPattern.matchEntire(file.name)

              match == null ||
                match.groupValues[2].toIntOrNull() == 1
            }
          }
          .sortedBy {
            it.relativePath.lowercase(Locale.ROOT)
          }

      if (archives.isEmpty()) {
        onProgress("No RAR archives found.")

        return@withContext Summary(
          archivesFound = 0,
          successful = 0,
          failed = 0,
          filesExtracted = 0,
          warnings = emptyList(),
          errors = emptyList(),
        )
      }

      var successful = 0
      var failed = 0
      var filesExtracted = 0

      val warnings = mutableListOf<String>()
      val errors = mutableListOf<String>()

      archives.forEachIndexed { index, archive ->

        currentCoroutineContext().ensureActive()

        val position = index + 1

        onProgress(
          "Preparing archive $position/${archives.size}: ${archive.name}",
        )

        var prepared: PreparedInput? = null

        try {
          val volumes = findVolumes(
            first = archive,
            allFiles = allFiles,
          )

          prepared =
            prepareInput(
              context = context,
              first = archive,
              volumes = volumes,
              onProgress = onProgress,
            )

          /*
           * Match the original shell script:
           *
           *   unrar t archive
           *   if test fails, warn but still attempt extraction.
           */
          try {
            onProgress(
              "Testing archive $position/${archives.size}: ${archive.name}",
            )

            testArchive(
              context = context,
              input = prepared,
            )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (testError: Exception) {

            warnings +=
              "${archive.name}: archive test failed; extraction will still be attempted " +
                "(${friendlyError(testError)})"

            onProgress(
              "Archive test failed; attempting extraction anyway.",
            )
          }

          currentCoroutineContext().ensureActive()

          onProgress(
            "Extracting archive $position/${archives.size}: ${archive.name}",
          )

          filesExtracted +=
            extractArchive(
              context = context,
              root = root,
              input = prepared,
            )

          successful++

          onProgress(
            "Finished: ${archive.name}",
          )
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {

          failed++

          errors +=
            "${archive.name}: ${friendlyError(error)}"

          onProgress(
            "Failed: ${archive.name} — ${friendlyError(error)}",
          )
        } finally {
          prepared?.cleanup()
        }
      }

      Summary(
        archivesFound = archives.size,
        successful = successful,
        failed = failed,
        filesExtracted = filesExtracted,
        warnings = warnings.take(10),
        errors = errors.take(10),
      )
    }

  private suspend fun scanTree(
    root: DocumentFile,
  ): List<ScannedFile> {

    val result = mutableListOf<ScannedFile>()

    val visited = hashSetOf(root.uri.toString())

    val pending =
      ArrayDeque<Pair<DocumentFile, String>>()

    pending.addLast(root to "")

    while (pending.isNotEmpty()) {

      currentCoroutineContext().ensureActive()

      val (directory, parentPath) =
        pending.removeLast()

      val children =
        directory
          .listFiles()
          .sortedBy {
            it.name.orEmpty().lowercase(Locale.ROOT)
          }

      children.forEach { child ->

        currentCoroutineContext().ensureActive()

        val name =
          child.name?.takeIf { it.isNotBlank() }
            ?: return@forEach

        val relativePath =
          if (parentPath.isEmpty()) {
            name
          } else {
            "$parentPath/$name"
          }

        if (child.isDirectory) {

          if (visited.add(child.uri.toString())) {
            pending.addLast(
              child to relativePath,
            )
          }

        } else {

          result +=
            ScannedFile(
              document = child,
              relativePath = relativePath,
            )
        }
      }
    }

    return result
  }

  private fun findVolumes(
    first: ScannedFile,
    allFiles: List<ScannedFile>,
  ): List<Volume> {

    /*
     * Modern scene-style multipart:
     *
     * Movie.part1.rar
     * Movie.part2.rar
     * Movie.part3.rar
     */
    partPattern.matchEntire(first.name)?.let { firstMatch ->

      val prefix = firstMatch.groupValues[1]

      return allFiles
        .asSequence()
        .filter { file ->
          file.parentPath == first.parentPath
        }
        .mapNotNull { file ->

          val match =
            partPattern.matchEntire(file.name)
              ?: return@mapNotNull null

          if (
            !match.groupValues[1]
              .equals(prefix, ignoreCase = true)
          ) {
            return@mapNotNull null
          }

          val number =
            match.groupValues[2].toIntOrNull()
              ?: return@mapNotNull null

          Volume(
            document = file.document,
            number = number,
          )
        }
        .sortedBy { it.number }
        .toList()
        .ifEmpty {
          listOf(
            Volume(
              document = first.document,
              number = 1,
            ),
          )
        }
    }

    /*
     * Traditional RAR volume style:
     *
     * Movie.rar
     * Movie.r00
     * Movie.r01
     * Movie.r02
     */
    val baseName =
      first.name.substringBeforeLast(
        ".",
        first.name,
      )

    val legacyVolumes =
      allFiles
        .asSequence()
        .filter { file ->
          file.parentPath == first.parentPath
        }
        .mapNotNull { file ->

          val match =
            legacyVolumePattern.matchEntire(file.name)
              ?: return@mapNotNull null

          if (
            !match.groupValues[1]
              .equals(baseName, ignoreCase = true)
          ) {
            return@mapNotNull null
          }

          val number =
            match.groupValues[2].toIntOrNull()
              ?: return@mapNotNull null

          Volume(
            document = file.document,
            number = number + 1,
          )
        }
        .sortedBy { it.number }
        .toList()

    return listOf(
      Volume(
        document = first.document,
        number = 1,
      ),
    ) + legacyVolumes
  }

  private suspend fun prepareInput(
    context: Context,
    first: ScannedFile,
    volumes: List<Volume>,
    onProgress: suspend (String) -> Unit,
  ): PreparedInput {

    if (volumes.size <= 1) {
      return PreparedInput(
        singleDocument = first.document,
      )
    }

    onProgress(
      "Preparing ${volumes.size} RAR volumes…",
    )

    val localPaths =
      volumes.mapNotNull { volume ->
        runCatching {
          volume.document.uri
            .resolveLocalPath(context)
        }.getOrNull()
          ?.takeIf { File(it).isFile }
      }

    /*
     * If Android can expose the SAF files as real filesystem paths,
     * libarchive can open the complete volume set directly.
     */
    if (localPaths.size == volumes.size) {
      return PreparedInput(
        volumePaths = localPaths,
      )
    }

    /*
     * Some SAF providers do not expose a filesystem path.
     *
     * Fall back to temporary files in the app cache. This is slower and
     * uses temporary storage, but it keeps multipart archives working
     * instead of silently treating part1 as a standalone archive.
     */
    val temporaryFiles = mutableListOf<File>()

    try {

      volumes.forEachIndexed { index, volume ->

        currentCoroutineContext().ensureActive()

        val temporary =
          File.createTempFile(
            "mpvrx_rar_${index}_",
            ".rar",
            context.cacheDir,
          )

        temporaryFiles += temporary

        val input =
          context.contentResolver
            .openInputStream(volume.document.uri)
            ?: throw IOException(
              "Cannot read RAR volume ${volume.document.name.orEmpty()}",
            )

        input.use { source ->
          temporary.outputStream().use { destination ->
            source.copyTo(
              destination,
              IO_BUFFER_SIZE,
            )
          }
        }
      }

      return PreparedInput(
        volumePaths = temporaryFiles.map(File::getAbsolutePath),
        temporaryFiles = temporaryFiles,
      )
    } catch (cancelled: CancellationException) {

      temporaryFiles.forEach(File::delete)

      throw cancelled
    } catch (error: Exception) {

      temporaryFiles.forEach(File::delete)

      throw error
    }
  }

  private suspend fun testArchive(
    context: Context,
    input: PreparedInput,
  ) {
    withArchive(
      context = context,
      input = input,
    ) { archive ->

      var entryCount = 0

      while (true) {

        currentCoroutineContext().ensureActive()

        val entry =
          Archive.readNextHeader(archive)

        if (entry == 0L) break

        entryCount++

        if (entryCount > MAX_ARCHIVE_ENTRIES) {
          throw IOException(
            "Archive contains too many entries.",
          )
        }

        if (ArchiveEntry.isEncrypted(entry)) {
          throw IOException(
            "Password-protected archives are not supported.",
          )
        }

        consumeCurrentEntry(
          archive = archive,
          output = null,
        )
      }
    }
  }

  private suspend fun extractArchive(
    context: Context,
    root: DocumentFile,
    input: PreparedInput,
  ): Int =
    withArchive(
      context = context,
      input = input,
    ) { archive ->

      var entryCount = 0
      var extracted = 0

      while (true) {

        currentCoroutineContext().ensureActive()

        val entry =
          Archive.readNextHeader(archive)

        if (entry == 0L) break

        entryCount++

        if (entryCount > MAX_ARCHIVE_ENTRIES) {
          throw IOException(
            "Archive contains too many entries.",
          )
        }

        val rawPath =
          ArchiveEntry.pathnameUtf8(entry)
            ?: ArchiveEntry
              .pathname(entry)
              ?.toString(StandardCharsets.UTF_8)

        val components =
          rawPath?.let(::safePathComponents)

        if (components == null) {
          Archive.readDataSkip(archive)
          continue
        }

        val mode =
          ArchiveEntry.mode(entry) and ArchiveEntry.AE_IFMT

        val normalizedPath =
          rawPath.replace('\\', '/')

        val isDirectory =
          mode == ArchiveEntry.AE_IFDIR ||
            normalizedPath.endsWith("/")

        if (isDirectory) {

          ensureDirectories(
            root = root,
            components = components,
          )

          Archive.readDataSkip(archive)
          continue
        }

        /*
         * Never extract symbolic links, hard links, devices or other
         * special filesystem entries.
         */
        if (mode != ArchiveEntry.AE_IFREG) {
          Archive.readDataSkip(archive)
          continue
        }

        if (ArchiveEntry.isEncrypted(entry)) {
          throw IOException(
            "Password-protected archives are not supported.",
          )
        }

        val parent =
          ensureDirectories(
            root = root,
            components = components.dropLast(1),
          )

        val fileName =
          components.last()

        val target =
          parent.findFile(fileName)?.let { existing ->

            if (existing.isDirectory) {
              throw IOException(
                "Cannot overwrite directory with file: $fileName",
              )
            }

            existing
          }
            ?: parent.createFile(
              "application/octet-stream",
              fileName,
            )
            ?: throw IOException(
              "Cannot create output file: $fileName",
            )

        val output =
          context.contentResolver
            .openOutputStream(
              target.uri,
              "wt",
            )
            ?: throw IOException(
              "Cannot write output file: $fileName",
            )

        output.use { destination ->

          consumeCurrentEntry(
            archive = archive,
            output = destination,
          )
        }

        extracted++
      }

      extracted
    }

  private suspend fun <T> withArchive(
    context: Context,
    input: PreparedInput,
    operation: suspend (Long) -> T,
  ): T {

    val archive =
      Archive.readNew()

    var descriptor: ParcelFileDescriptor? = null

    try {

      Archive.setCharset(
        archive,
        StandardCharsets.UTF_8
          .name()
          .toByteArray(StandardCharsets.UTF_8),
      )

      Archive.readSupportFormatRar(archive)
      Archive.readSupportFormatRar5(archive)

      if (input.isVolumeSet) {

        val names =
          input.volumePaths
            .map {
              it.toByteArray(StandardCharsets.UTF_8)
            }
            .toTypedArray()

        Archive.readOpenFileNames(
          archive,
          names,
          IO_BUFFER_SIZE.toLong(),
        )

      } else {

        val document =
          input.singleDocument
            ?: throw IOException(
              "RAR input is unavailable.",
            )

        descriptor =
          context.contentResolver
            .openFileDescriptor(
              document.uri,
              "r",
            )
            ?: throw IOException(
              "Cannot open RAR archive.",
            )

        Archive.readOpenFd(
          archive,
          descriptor.fd,
          IO_BUFFER_SIZE.toLong(),
        )
      }

      return operation(archive)

    } finally {

      runCatching {
        Archive.free(archive)
      }

      runCatching {
        descriptor?.close()
      }
    }
  }

  private suspend fun consumeCurrentEntry(
    archive: Long,
    output: OutputStream?,
  ) {

    val buffer =
      ByteBuffer.allocateDirect(
        IO_BUFFER_SIZE,
      )

    while (true) {

      currentCoroutineContext().ensureActive()

      buffer.clear()

      Archive.readData(
        archive,
        buffer,
      )

      val count =
        buffer.position()

      if (count <= 0) break

      if (output != null) {

        buffer.flip()

        val chunk =
          ByteArray(count)

        buffer.get(chunk)

        output.write(chunk)
      }
    }
  }

  private fun safePathComponents(
    path: String,
  ): List<String>? {

    val normalized =
      path.replace('\\', '/')

    if (
      normalized.startsWith("/") ||
      normalized.contains('\u0000') ||
      Regex("""^[A-Za-z]:$""")
        .containsMatchIn(
          normalized.substringBefore('/'),
        )
    ) {
      return null
    }

    val components =
      normalized
        .split('/')
        .filter {
          it.isNotEmpty() &&
            it != "."
        }

    if (
      components.isEmpty() ||
      components.any {
        it == ".." ||
          it.contains('\u0000')
      }
    ) {
      return null
    }

    return components
  }

  private fun ensureDirectories(
    root: DocumentFile,
    components: List<String>,
  ): DocumentFile {

    var current = root

    components.forEach { name ->

      val existing =
        current.findFile(name)

      current =
        when {

          existing?.isDirectory == true ->
            existing

          existing != null ->
            throw IOException(
              "Cannot create directory because a file already exists: $name",
            )

          else ->
            current.createDirectory(name)
              ?: throw IOException(
                "Cannot create directory: $name",
              )
        }
    }

    return current
  }

  private fun friendlyError(
    error: Throwable,
  ): String =
    error.message
      ?.takeIf { it.isNotBlank() }
      ?: error.javaClass.simpleName
}

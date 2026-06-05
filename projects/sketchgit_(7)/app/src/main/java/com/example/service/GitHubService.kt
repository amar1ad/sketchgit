package com.example.service

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val TAG = "GitHubService"

    interface UploadProgress {
        fun onProgress(current: Int, total: Int, fileName: String)
        fun onMessage(message: String)
        fun onError(message: String)
        fun onFinished(success: Boolean)
    }

    /**
     * Checks if a file exists on GitHub and returns its SHA if present.
     * Returns null if file does not exist, or throws Exception if request fails.
     */
    private fun getFileSha(
        token: String,
        repoOwnerAndName: String,
        branch: String,
        filePath: String
    ): String? {
        val url = "https://api.github.com/repos/$repoOwnerAndName/contents/$filePath?ref=$branch"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                val json = JSONObject(response.body?.string() ?: "")
                return json.optString("sha", null)
            } else if (response.code == 404) {
                return null
            } else {
                throw IOException("GitHub API SHA request failed with code: ${response.code}, message: ${response.message}")
            }
        }
    }

    /**
     * Uploads/Overwrites a single file to GitHub with Base64 content.
     */
    fun uploadFile(
        token: String,
        repoOwnerAndName: String,
        branch: String,
        filePath: String,
        contentBytes: ByteArray,
        commitMessage: String
    ): Boolean {
        // 1. Get SHA if file already exists on GitHub
        val sha = try {
            getFileSha(token, repoOwnerAndName, branch, filePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed retrieving file SHA for $filePath, attempting initial commit", e)
            null
        }

        // 2. Base64 encode file contents
        val base64Content = Base64.encodeToString(contentBytes, Base64.NO_WRAP)

        // 3. Build PUT payload
        val jsonPayload = JSONObject().apply {
            put("message", commitMessage)
            put("content", base64Content)
            put("branch", branch)
            if (sha != null) {
                put("sha", sha)
            }
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toString().toRequestBody(mediaType)

        val url = "https://api.github.com/repos/$repoOwnerAndName/contents/$filePath"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200 || response.code == 201) {
                Log.d(TAG, "Uploaded file successfully: $filePath")
                return true
            } else {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "Upload failed for $filePath: code=${response.code} body=$errorBody")
                throw IOException("Commit failed for $filePath with code: ${response.code}, error: $errorBody")
            }
        }
    }

    /**
     * Syncs a whole Sketchware project folder as either a combined ZIP backup or as an entire Source Code Tree.
     */
    fun syncSketchwareProject(
        token: String,
        repoOwnerAndName: String,
        branch: String,
        projectFolder: File,
        projectName: String,
        projectId: String,
        syncMode: String, // "ZIP" or "SOURCE_TREE"
        fixColors: Boolean,
        fixManifest: Boolean,
        progressListener: UploadProgress
    ) {
        if (!projectFolder.exists()) {
            progressListener.onError("مجلد المشروع غير موجود: ${projectFolder.absolutePath}")
            progressListener.onFinished(false)
            return
        }

        val isZipSource = projectFolder.isFile && projectFolder.name.endsWith(".zip", ignoreCase = true)

        if (syncMode == "ZIP") {
            try {
                progressListener.onMessage("جاري تحضير النسخة الاحتياطية المضغوطة وتحسين محتواها...")
                // Temp output zip file
                val tempZip = File.createTempFile("sc_backup_", ".zip")
                tempZip.deleteOnExit()

                if (isZipSource) {
                    if (fixColors || fixManifest) {
                        progressListener.onMessage("جاري فك ضغط الأرشيف مؤقتاً لتصحيح الألوان والـ Manifest...")
                        val tempExtractDir = File(projectFolder.parentFile, "temp_zip_extract_${System.currentTimeMillis()}")
                        tempExtractDir.mkdirs()
                        SketchwareUtils.decompressZipToFolder(projectFolder, tempExtractDir)
                        
                        val compressed = SketchwareUtils.compressFolderToZip(tempExtractDir, tempZip, applyFixes = fixColors || fixManifest)
                        tempExtractDir.deleteRecursively()
                        if (!compressed) {
                            progressListener.onError("فشل في ضغط وتصفية الأرشيف.")
                            progressListener.onFinished(false)
                            return
                        }
                    } else {
                        projectFolder.copyTo(tempZip, overwrite = true)
                    }
                } else {
                    val compressed = SketchwareUtils.compressFolderToZip(projectFolder, tempZip, applyFixes = fixColors || fixManifest)
                    if (!compressed) {
                        progressListener.onError("فشل في ضغط وتجهيز مجلد النسخة الاحتياطية.")
                        progressListener.onFinished(false)
                        return
                    }
                }

                progressListener.onMessage("جاري رفع ملف النسخة الاحتياطية المضغوطة إلى GitHub...")
                val zipBytes = tempZip.readBytes()
                val targetName = "${projectName.replace(" ", "_")}_backup_${projectId}.zip"
                val repoPath = "backups/$targetName"

                val success = uploadFile(
                    token = token,
                    repoOwnerAndName = repoOwnerAndName,
                    branch = branch,
                    filePath = repoPath,
                    contentBytes = zipBytes,
                    commitMessage = "Backup Sketchware Project: $projectName (ID: $projectId) - Stable Backup"
                )

                if (success) {
                    progressListener.onMessage("تم رفع النسخة الاحتياطية ($targetName) بنجام!")
                    progressListener.onFinished(true)
                } else {
                    progressListener.onError("فشل رفع الملف إلى المستودع.")
                    progressListener.onFinished(false)
                }

                // Clean-up
                tempZip.delete()
            } catch (e: Exception) {
                progressListener.onError("خطاء أثناء المزامنة: ${e.localizedMessage ?: "عطل غير معروف"}")
                progressListener.onFinished(false)
            }
        } else {
            // SOURCE_TREE SYNC: Push all files recursively
            var workingFolder = projectFolder
            var tempExtractDir: File? = null
            try {
                if (isZipSource) {
                    progressListener.onMessage("جاري استخراج ملفات الكود من الأرشيف المضغوط لتهيئتها للرفع الهيكلي...")
                    tempExtractDir = File(projectFolder.parentFile, "temp_src_extract_${System.currentTimeMillis()}")
                    tempExtractDir.mkdirs()
                    val extracted = SketchwareUtils.decompressZipToFolder(projectFolder, tempExtractDir)
                    if (!extracted) {
                        progressListener.onError("فشل في فك الأرشيف لتحديد شجرة ملفات السورس كود.")
                        progressListener.onFinished(false)
                        return
                    }
                    workingFolder = tempExtractDir
                }

                progressListener.onMessage("جاري فحص ملفات المشروع لتصحيح الألوان والصلاحيات والرفع كملفات برمجية...")
                val allFiles = mutableListOf<File>()
                gatherUploadFiles(workingFolder, workingFolder, allFiles)

                if (allFiles.isEmpty()) {
                    progressListener.onError("لا توجد ملفات صالحة للرفع في مجلد مشروع Sketchware.")
                    progressListener.onFinished(false)
                    return
                }

                val totalCount = allFiles.size
                progressListener.onMessage("تم العثور على $totalCount ملفاً. جاري الرفع التدريجي...")

                var successCount = 0
                for ((index, file) in allFiles.withIndex()) {
                    val relativePath = file.relativeTo(workingFolder).path
                    val fileName = file.name
                    progressListener.onProgress(index + 1, totalCount, relativePath)

                    try {
                        var fileBytes = file.readBytes()
                        // Apply automatic Sketchware XML structure repairs if required
                        if (fileName == "AndroidManifest.xml" && fixManifest) {
                            val rawText = String(fileBytes, Charsets.UTF_8)
                            val cleaned = SketchwareUtils.fixAndroidManifest(rawText)
                            fileBytes = cleaned.toByteArray(Charsets.UTF_8)
                            Log.d(TAG, "Processed modifications and cleaned AndroidManifest.xml before push")
                        } else if (fileName == "colors.xml" && fixColors) {
                            val rawText = String(fileBytes, Charsets.UTF_8)
                            val cleaned = SketchwareUtils.fixColorsXml(rawText)
                            fileBytes = cleaned.toByteArray(Charsets.UTF_8)
                            Log.d(TAG, "Processed modifications and cleaned colors.xml before push")
                        }

                        // Upload the cleaned file to GitHub
                        // Place under dedicated folder to avoid root collisions
                        val cleanProjectName = projectName.replace(" ", "_")
                        val repoPath = "projects/$cleanProjectName/$relativePath"

                        val fileSuccess = uploadFile(
                            token = token,
                            repoOwnerAndName = repoOwnerAndName,
                            branch = branch,
                            filePath = repoPath,
                            contentBytes = fileBytes,
                            commitMessage = "Updated project file: $relativePath via SketchGit"
                        )
                        if (fileSuccess) {
                            successCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed uploading individual file: $relativePath", e)
                        // Log message and continue with next file to keep upload robust
                        progressListener.onMessage("تنبيه: فشل رفع $relativePath. تابع تخطي لتأمين المزامنة.")
                    }
                }

                progressListener.onMessage("اكتملت مزامنة الملفات. تم رفع $successCount من إجمالي $totalCount ملفات.")
                progressListener.onFinished(successCount > 0)
            } catch (e: Exception) {
                progressListener.onError("عطل في المزامنة المتسلسلة: ${e.localizedMessage}")
                progressListener.onFinished(false)
            } finally {
                tempExtractDir?.deleteRecursively()
            }
        }
    }

    private fun gatherUploadFiles(root: File, current: File, list: MutableList<File>) {
        if (current.isDirectory) {
            val name = current.name
            // Skip common temporary bin folders & dot git structures
            if (name == "bin" || name == "gen" || name == "build" || name == ".git" || name == "local.properties") {
                return
            }
            val files = current.listFiles() ?: return
            for (file in files) {
                gatherUploadFiles(root, file, list)
            }
        } else {
            list.add(current)
        }
    }

    /**
     * Fetches all repositories of the authenticated user from GitHub.
     */
    fun getUserRepositories(token: String): List<Map<String, String>> {
        val url = "https://api.github.com/user/repos?sort=updated&per_page=100"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                val responseBody = response.body?.string() ?: ""
                val reposArray = org.json.JSONArray(responseBody)
                val repoList = mutableListOf<Map<String, String>>()
                for (i in 0 until reposArray.length()) {
                    val repoJson = reposArray.getJSONObject(i)
                    val name = repoJson.getString("name")
                    val fullName = repoJson.getString("full_name")
                    val description = repoJson.optString("description", "")
                    val isPrivate = repoJson.optBoolean("private", false)
                    repoList.add(mapOf(
                        "name" to name,
                        "fullName" to fullName,
                        "description" to description,
                        "isPrivate" to isPrivate.toString()
                    ))
                }
                return repoList
            } else {
                throw IOException("فشل جلب مستودعات GitHub بكود: ${response.code}")
            }
        }
    }

    /**
     * Lists backup files inside the backups/ directory in a given repository.
     */
    fun listRepoBackups(token: String, repoOwnerAndName: String, branch: String): List<Map<String, String>> {
        val url = "https://api.github.com/repos/$repoOwnerAndName/contents/backups?ref=$branch"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 200) {
                val responseBody = response.body?.string() ?: ""
                val filesArray = org.json.JSONArray(responseBody)
                val fileList = mutableListOf<Map<String, String>>()
                for (i in 0 until filesArray.length()) {
                    val fileJson = filesArray.getJSONObject(i)
                    val name = fileJson.getString("name")
                    val path = fileJson.getString("path")
                    val downloadUrl = fileJson.optString("download_url", "")
                    val size = fileJson.optLong("size", 0).toString()
                    fileList.add(mapOf(
                        "name" to name,
                        "path" to path,
                        "downloadUrl" to downloadUrl,
                        "size" to size
                    ))
                }
                return fileList
            } else if (response.code == 404) {
                return emptyList()
            } else {
                throw IOException("فشل جلب ملفات النسخ الاحتياطي بكود: ${response.code}")
            }
        }
    }

    /**
     * Downloads a file from GitHub by direct raw URL and writes it locally.
     */
    fun downloadFile(token: String, downloadUrl: String, destFile: File) {
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Authorization", "token $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val byteStream = response.body?.byteStream() ?: throw IOException("Empty body stream response")
                destFile.outputStream().use { fos ->
                    byteStream.copyTo(fos)
                }
            } else {
                throw IOException("فشل تحميل الملف بكود: ${response.code}")
            }
        }
    }

    /**
     * Creates a new GitHub repository for the authenticated user.
     */
    fun createUserRepository(
        token: String,
        name: String,
        description: String,
        isPrivate: Boolean
    ): Boolean {
        val url = "https://api.github.com/user/repos"
        val jsonPayload = org.json.JSONObject().apply {
            put("name", name)
            put("description", description)
            put("private", isPrivate)
            put("auto_init", false) // Do not auto-initialize so it's clean for direct source push
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            return response.code == 201
        }
    }
}

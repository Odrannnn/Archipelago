package eu.odran.archipelago

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Runs an APWorld's registered desktop client in a disposable app process.
 *
 * Launcher components are intentionally long-lived. Killing only this process
 * after the client emits its ROM keeps the generator's primary Chaquopy runtime
 * usable and prevents desktop emulator-launch code from surviving the patch.
 */
class ComponentPatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val receiver = request.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
            ?: return START_NOT_STICKY
        val patchPath = request.getStringExtra(EXTRA_PATCH_PATH)
        val inputUris = request.getStringExtra(EXTRA_INPUT_URIS)
        val outputUri = request.getStringExtra(EXTRA_OUTPUT_URI)
        if (patchPath == null || inputUris == null || outputUri == null) {
            receiver.send(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, "Incomplete patch request") })
            stopWorker(startId)
            return START_NOT_STICKY
        }

        thread(name = "apworld-component-patcher") {
            val inputs = mutableListOf<android.os.ParcelFileDescriptor>()
            var output: android.os.ParcelFileDescriptor? = null
            try {
                val descriptors = JSONObject()
                val uriRoot = JSONObject(inputUris)
                uriRoot.keys().forEach { key ->
                    val descriptor = contentResolver.openFileDescriptor(Uri.parse(uriRoot.getString(key)), "r")
                        ?: error("Could not open ROM input $key")
                    inputs += descriptor
                    descriptors.put(key, descriptor.fd)
                }
                output = contentResolver.openFileDescriptor(Uri.parse(outputUri), "rwt")
                    ?: error("Could not open the selected ROM destination")
                OfflineGenerator.python(this).getModule("offline_generator").callAttr(
                    "patch_component_rom",
                    patchPath,
                    descriptors.toString(),
                    output.fd,
                    OfflineGenerator.workDirectory(this).absolutePath,
                    COMPONENT_TIMEOUT_SECONDS,
                )
                receiver.send(RESULT_OK, Bundle.EMPTY)
            } catch (error: Throwable) {
                receiver.send(RESULT_ERROR, Bundle().apply {
                    putString(EXTRA_ERROR, error.message ?: error.javaClass.simpleName)
                })
            } finally {
                output?.close()
                inputs.forEach { it.close() }
                stopWorker(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun stopWorker(startId: Int) {
        stopSelf(startId)
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }

    companion object {
        private const val EXTRA_RECEIVER = "receiver"
        private const val EXTRA_PATCH_PATH = "patch_path"
        private const val EXTRA_INPUT_URIS = "input_uris"
        private const val EXTRA_OUTPUT_URI = "output_uri"
        private const val EXTRA_ERROR = "error"
        private const val RESULT_OK = 1
        private const val RESULT_ERROR = 2
        private const val COMPONENT_TIMEOUT_SECONDS = 30 * 60

        fun patchBlocking(
            context: Context,
            patch: ByteArray,
            romInputs: Map<String, Uri>,
            output: Uri,
        ) {
            val jobsRoot = File(OfflineGenerator.workDirectory(context), "component-patch-jobs").apply {
                check(isDirectory || mkdirs()) { "Could not create component patch workspace" }
            }
            val job = File(jobsRoot, UUID.randomUUID().toString()).apply {
                check(mkdir()) { "Could not create component patch job" }
            }
            val extension = OfflineGenerator.patchFileExtension(context, patch)
            val patchFile = File(job, "player$extension").apply { writeBytes(patch) }
            val result = AtomicReference<Throwable?>()
            val completed = CountDownLatch(1)
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode != RESULT_OK) {
                        result.set(IllegalStateException(
                            resultData?.getString(EXTRA_ERROR) ?: "The APWorld client component failed",
                        ))
                    }
                    completed.countDown()
                }
            }
            val inputs = JSONObject().apply {
                romInputs.forEach { (key, uri) -> put(key, uri.toString()) }
            }
            try {
                context.startService(Intent(context, ComponentPatchService::class.java).apply {
                    putExtra(EXTRA_RECEIVER, receiver)
                    putExtra(EXTRA_PATCH_PATH, patchFile.absolutePath)
                    putExtra(EXTRA_INPUT_URIS, inputs.toString())
                    putExtra(EXTRA_OUTPUT_URI, output.toString())
                }) ?: error("Android refused to start the APWorld component patch worker")
                if (!completed.await(COMPONENT_TIMEOUT_SECONDS + 30L, TimeUnit.SECONDS)) {
                    context.stopService(Intent(context, ComponentPatchService::class.java))
                    error("The APWorld client component did not finish within 30 minutes")
                }
                result.get()?.let { throw it }
            } finally {
                job.deleteRecursively()
            }
        }
    }
}

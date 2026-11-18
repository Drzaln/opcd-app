package dev.opencode.mobile.ui.editor

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.mobile.data.net.HelperManager
import dev.opencode.mobile.data.net.HelperState
import dev.opencode.mobile.ui.theme.OcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** idle → starting → live, or error (with a message). */
data class ScreenUi(
    val state: String = "idle",
    val serial: String? = null,
    val deviceName: String? = null,
    /** Decoded video size (changes when the device rotates). */
    val width: Int = 0,
    val height: Int = 0,
    val message: String? = null,
)

/**
 * H.264 from the helper's scrcpy relay → MediaCodec → a Surface. Packets arrive on OkHttp's reader
 * thread as `[kind:1][pts:8][data]` (1 config, 2 frame, 3 key frame); one feeder thread queues them
 * into the codec, one drainer renders. A config packet after the first one (rotation) rebuilds the
 * codec.
 */
private class H264Decoder(private val surface: Surface, private val onSize: (Int, Int) -> Unit) {
    private val queue = LinkedBlockingQueue<ByteArray>(600)
    @Volatile private var running = true
    private var codec: MediaCodec? = null
    private var config: ByteArray? = null
    private val feeder = Thread({ feed() }, "screen-feed").apply { start() }

    fun offer(packet: ByteArray) {
        if (!queue.offer(packet)) { queue.clear(); queue.offer(packet) }
    }

    private fun newCodec(csd: ByteArray): MediaCodec {
        codec?.let { runCatching { it.stop(); it.release() } }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        Thread({ drain(c) }, "screen-drain").start()
        return c
    }

    private fun feed() {
        while (running) {
            // release() interrupts this wait — that's the normal way out, not a crash.
            val packet = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return } ?: continue
            if (packet.size < 9) continue
            val kind = packet[0].toInt()
            val pts = ByteBuffer.wrap(packet, 1, 8).long
            val data = packet.copyOfRange(9, packet.size)
            try {
                if (kind == 1) {
                    if (config != null && !config.contentEquals(data)) newCodec(data) else if (codec == null) newCodec(data)
                    config = data
                    continue
                }
                val c = codec ?: continue
                val index = c.dequeueInputBuffer(50_000)
                if (index < 0) continue
                val buf = c.getInputBuffer(index) ?: continue
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(index, 0, data.size, pts, if (kind == 3) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (_: Exception) {
                // A codec torn down mid-call (rotation, stop) — the next config packet rebuilds it.
            }
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running && codec === c) {
            try {
                val index = c.dequeueOutputBuffer(info, 100_000)
                when {
                    index >= 0 -> c.releaseOutputBuffer(index, true)
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                        onSize(w, h)
                    }
                }
            } catch (_: Exception) {
                return
            }
        }
    }

    fun release() {
        running = false
        feeder.interrupt()
        codec?.let { runCatching { it.stop(); it.release() } }
        codec = null
    }
}

class ScreenController(private val scope: CoroutineScope, private val helper: HelperManager) {
    private val _ui = MutableStateFlow(ScreenUi())
    val ui: StateFlow<ScreenUi> = _ui
    private var decoder: H264Decoder? = null

    init {
        scope.launch {
            helper.notifications.collect { msg ->
                if ((msg["method"] as? JsonPrimitive)?.content != "screen.end") return@collect
                val reason = msg["params"]?.jsonObject?.get("reason")?.let { (it as? JsonPrimitive)?.contentOrNull }
                stopLocal()
                _ui.update { it.copy(state = "error", message = reason ?: "Screen stream ended") }
            }
        }
        scope.launch { helper.state.collect { if (it !is HelperState.Ready && _ui.value.state != "idle") stopLocal() } }
    }

    /** Called when the SurfaceView's surface exists and a device is chosen. */
    fun start(serial: String, surface: Surface) {
        stopLocal()
        val d = H264Decoder(surface) { w, h ->
            _ui.update { it.copy(width = w, height = h) }
            helper.send("screen.resize", buildJsonObject { put("width", w); put("height", h) })
        }
        decoder = d
        helper.binaryListener = { d.offer(it) }
        _ui.update { ScreenUi(state = "starting", serial = serial) }
        scope.launch {
            try {
                val res = helper.request("screen.start", buildJsonObject {
                    put("serial", serial); put("maxSize", 800); put("bitRate", 1_500_000); put("maxFps", 30)
                }, timeoutMs = 45_000).jsonObject
                if (decoder !== d) return@launch
                _ui.update { it.copy(
                    state = "live",
                    width = (res["width"] as? JsonPrimitive)?.intOrNull ?: it.width,
                    height = (res["height"] as? JsonPrimitive)?.intOrNull ?: it.height,
                    deviceName = (res["deviceName"] as? JsonPrimitive)?.contentOrNull,
                ) }
            } catch (e: Exception) {
                if (decoder === d) { stopLocal(); _ui.update { it.copy(state = "error", message = e.message) } }
            }
        }
    }

    fun stop() {
        // Always tell the helper, even if our decoder is already gone (a reconnect cleared it).
        helper.send("screen.stop", buildJsonObject { })
        stopLocal()
        _ui.update { it.copy(state = "idle") }
    }

    private fun stopLocal() {
        helper.binaryListener = null
        decoder?.release()
        decoder = null
    }

    /** action 0 down, 1 up, 2 move; (x, y) in a w×h frame the helper scales to the video. */
    fun touch(action: Int, pointer: Long, x: Float, y: Float, w: Int, h: Int) {
        if (_ui.value.state != "live") return
        helper.send("screen.input", buildJsonObject { put("action", action); put("pointer", pointer); put("x", x); put("y", y); put("w", w); put("h", h) })
    }

    fun key(name: String) {
        if (_ui.value.state == "live") helper.send("screen.input", buildJsonObject { put("key", name) })
    }

    companion object {
        fun available(state: HelperState) = "screen" in HelperManager.features(state)
    }
}

/**
 * The live screen of [serial] (aspect-fit), with touch and a Back · Home · Recents · Dev menu · Stop
 * toolbar. The stream runs while this is on screen.
 */
@Composable
fun DeviceScreen(screen: ScreenController, serial: String?, deviceLabel: String?, isThisPhone: Boolean, modifier: Modifier = Modifier) {
    val ui by screen.ui.collectAsState()
    val c = OcTheme.colors
    var surface by remember { mutableStateOf<Surface?>(null) }
    var wanted by remember(serial) { mutableStateOf(!isThisPhone) }
    var attempt by remember { mutableStateOf(0) }
    DisposableEffect(serial, surface, wanted, attempt) {
        val s = surface
        // Captured now: `wanted` is a state delegate, so reading it inside onDispose would see the
        // *new* value (false after tapping Stop) and skip telling the helper to stop.
        val active = serial != null && s != null && wanted && !isSimulatorSerial(serial)
        if (active) screen.start(serial!!, s!!)
        onDispose { if (active) screen.stop() }
    }
    Column(modifier.background(c.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { screen.key("back") }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = c.textSecondary) }
            IconButton(onClick = { screen.key("home") }) { Icon(Icons.Outlined.Circle, contentDescription = "Home", tint = c.textSecondary) }
            IconButton(onClick = { screen.key("recents") }) { Icon(Icons.Outlined.CropSquare, contentDescription = "Recent apps", tint = c.textSecondary) }
            IconButton(onClick = { screen.key("menu") }) { Icon(Icons.Outlined.Menu, contentDescription = "Dev menu", tint = c.textSecondary) }
            IconButton(onClick = { wanted = !wanted }) {
                Icon(Icons.Outlined.StopCircle, contentDescription = if (wanted) "Stop screen" else "Show screen", tint = if (wanted) c.errorText else c.textSecondary)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = if (ui.width > 0 && ui.height > 0) ui.width.toFloat() / ui.height else 9f / 19.5f
            var size by remember { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .onSizeChanged { size = it }
                    .pointerInput(ui.state) {
                        awaitPointerEventScope {
                            while (true) {
                                // Initial pass: take the gesture before the bottom sheet (or a scroll parent)
                                // can turn a swipe into dragging the sheet.
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                for (change in event.changes) {
                                    val action = when {
                                        change.pressed && !change.previousPressed -> 0
                                        !change.pressed && change.previousPressed -> 1
                                        change.pressed -> 2
                                        else -> continue
                                    }
                                    screen.touch(action, change.id.value, change.position.x, change.position.y, size.width, size.height)
                                    change.consume()
                                }
                            }
                        }
                    },
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) { surface = h.surface }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) { surface = null }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                val overlay = when {
                    serial == null -> "Pick a device (or start an emulator) to see its screen."
                    isSimulatorSerial(serial) -> "The live screen isn't available for iOS simulators yet. Turn on \"Show the Simulator window\" to see it on the Mac."
                    !wanted && isThisPhone -> "${deviceLabel ?: "This device"} is the phone you're holding — mirroring it shows this screen inside itself."
                    !wanted -> "Screen stopped."
                    ui.state == "starting" -> "Connecting to ${deviceLabel ?: serial}…"
                    ui.state == "error" -> ui.message ?: "Screen stream ended"
                    else -> null
                }
                if (overlay != null) {
                    Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(overlay, color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                        if (serial != null && !isSimulatorSerial(serial) && (!wanted || ui.state == "error")) TextButton(onClick = { wanted = true; attempt++ }) { Text(if (ui.state == "error") "Retry" else "Show screen") }
                    }
                }
            }
        }
    }
}

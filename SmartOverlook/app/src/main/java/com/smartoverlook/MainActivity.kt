package com.smartoverlook

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

data class VoiceCommand(val phrases: String, val payload: String)

class MainActivity : Activity() {
    private val navy = Color.rgb(16, 27, 45)
    private val panel = Color.rgb(27, 43, 66)
    private val blue = Color.rgb(55, 151, 235)
    private val pale = Color.rgb(226, 238, 250)
    private val muted = Color.rgb(157, 178, 201)

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var statusText: TextView
    private lateinit var recognizedText: TextView
    private lateinit var connectButton: Button
    private lateinit var commandsContainer: LinearLayout
    private val outputStates = BooleanArray(8)
    private val commands = mutableListOf<VoiceCommand>()

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        loadCommands()
        buildScreen()
        updateConnectionUi()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(content)
        root.addView(scroll, ViewGroup.LayoutParams(-1, -1))

        val header = TextView(this).apply {
            text = "SMART OVERLOOK"
            textSize = 25f
            setTextColor(pale)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(8), 0, dp(4))
        }
        content.addView(header)
        content.addView(label("کنترل صوتی هوشمند آردوینو", 14f, muted, Gravity.CENTER))
        content.addView(space(18))

        val connectionCard = card()
        connectionCard.addView(label("اتصال بلوتوث", 17f, pale))
        statusText = label("وضعیت: متصل نیست", 14f, muted)
        connectionCard.addView(statusText)
        connectButton = Button(this).apply {
            text = "انتخاب و اتصال به HC-05"
            setOnClickListener { requestBluetoothAndConnect() }
        }
        connectionCard.addView(connectButton)
        content.addView(connectionCard)
        content.addView(space(14))

        val voiceCard = card()
        voiceCard.addView(label("فرمان صوتی فارسی", 17f, pale))
        recognizedText = label("فرمان تشخیص‌داده‌شده اینجا نمایش داده می‌شود", 14f, muted)
        voiceCard.addView(recognizedText)
        val mic = Button(this).apply {
            text = "🎙  برای صحبت لمس کن"
            textSize = 17f
            setOnClickListener { startPersianRecognition() }
        }
        voiceCard.addView(mic)
        voiceCard.addView(label("برای تشخیص گفتار، اینترنت و سرویس گفتار فارسی گوشی لازم است.", 12f, muted))
        content.addView(voiceCard)
        content.addView(space(14))

        val outputCard = card()
        outputCard.addView(label("کنترل دستی ۸ خروجی", 17f, pale))
        val grid = GridLayout(this).apply {
            columnCount = 2
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        for (i in 0 until 8) {
            val button = Button(this).apply {
                text = "خروجی ${i + 1}  •  خاموش"
                textSize = 13f
                setOnClickListener {
                    val next = !outputStates[i]
                    sendPayload("OUT${i + 1}_${if (next) "ON" else "OFF"}")
                    outputStates[i] = next
                    text = "خروجی ${i + 1}  •  ${if (next) "روشن" else "خاموش"}"
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(58)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(button, params)
        }
        outputCard.addView(grid)
        val allOff = Button(this).apply {
            text = "خاموش کردن همه خروجی‌ها"
            setOnClickListener {
                sendPayload("ALL_OFF")
                for (i in outputStates.indices) outputStates[i] = false
                rebuildScreenOutputs()
            }
        }
        outputCard.addView(allOff)
        content.addView(outputCard)
        content.addView(space(14))

        val commandCard = card()
        commandCard.addView(label("مدیریت دستورات صوتی", 17f, pale))
        commandCard.addView(label("برای هر وسیله می‌توانی عبارت فارسی دلخواه و فرمان متناظر را تعریف کنی.", 12f, muted))
        commandsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        commandCard.addView(commandsContainer)
        val add = Button(this).apply {
            text = "+ افزودن دستور جدید"
            setOnClickListener { showCommandEditor(null) }
        }
        commandCard.addView(add)
        content.addView(commandCard)

        setContentView(root)
        renderCommands()
    }

    private fun rebuildScreenOutputs() {
        // State is also reflected in the command buttons after the next screen refresh.
        Toast.makeText(this, "فرمان خاموشی همه خروجی‌ها ارسال شد", Toast.LENGTH_SHORT).show()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        setBackgroundColor(panel)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        elevation = dp(2).toFloat()
    }.also { /* margin is applied by wrapper below */ }

    private fun label(textValue: String, size: Float, color: Int, gravityValue: Int = Gravity.RIGHT): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            gravity = gravityValue
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestBluetoothAndConnect() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
            return
        }
        showPairedDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showPairedDevices()
        } else if (requestCode == 102 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchSpeechRecognizer()
        } else {
            Toast.makeText(this, "مجوز لازم داده نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            toast("این گوشی بلوتوث ندارد")
            return
        }
        if (!adapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 103)
            return
        }
        val devices = try { adapter.bondedDevices.toList() } catch (_: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            toast("ابتدا HC-05 را از تنظیمات بلوتوث گوشی جفت‌سازی کن")
            return
        }
        val names = devices.map { "${it.name ?: "Bluetooth"}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("انتخاب ماژول بلوتوث")
            .setItems(names) { _, which -> connectToDevice(devices[which]) }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        statusText.text = "وضعیت: در حال اتصال..."
        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket.connect()
                bluetoothSocket = socket
                outputStream = socket.outputStream
                runOnUiThread {
                    statusText.text = "وضعیت: متصل به ${device.name ?: "HC-05"}"
                    connectButton.text = "اتصال برقرار است"
                    toast("اتصال بلوتوث برقرار شد")
                }
            } catch (e: Exception) {
                try { bluetoothSocket?.close() } catch (_: Exception) {}
                bluetoothSocket = null
                outputStream = null
                runOnUiThread {
                    statusText.text = "وضعیت: اتصال ناموفق"
                    toast("اتصال ناموفق؛ جفت‌سازی و روشن بودن HC-05 را بررسی کن")
                }
            }
        }.start()
    }

    private fun updateConnectionUi() {
        statusText.text = if (outputStream == null) "وضعیت: متصل نیست" else "وضعیت: متصل"
    }

    private fun sendPayload(payload: String) {
        val stream = outputStream
        if (stream == null) {
            toast("ابتدا به HC-05 متصل شو")
            return
        }
        Thread {
            try {
                stream.write((payload + "\n").toByteArray(Charsets.US_ASCII))
                stream.flush()
            } catch (_: Exception) {
                runOnUiThread {
                    statusText.text = "وضعیت: ارتباط قطع شده"
                    outputStream = null
                    toast("ارسال فرمان انجام نشد")
                }
            }
        }.start()
    }

    private fun startPersianRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("سرویس تشخیص گفتار روی گوشی در دسترس نیست")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }
        launchSpeechRecognizer()
    }

    private fun launchSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { recognizedText.text = "در حال گوش دادن..." } }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { runOnUiThread { recognizedText.text = "در حال تشخیص گفتار..." } }
                override fun onError(error: Int) { runOnUiThread { recognizedText.text = "تشخیص انجام نشد؛ دوباره تلاش کن" } }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull().orEmpty()
                    runOnUiThread {
                        recognizedText.text = if (spoken.isBlank()) "گفتاری تشخیص داده نشد" else "شنیده شد: $spoken"
                        matchAndSend(spoken)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "فرمان خود را به فارسی بگویید")
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            toast("شروع تشخیص گفتار ممکن نشد")
        }
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace('ي', 'ی').replace('ك', 'ک')
            .replace("‌", " ").replace(Regex("[ًٌٍَُِّْٰـ]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun matchAndSend(spoken: String) {
        val normalized = normalize(spoken)
        if (normalized.isBlank()) return
        val found = commands.firstOrNull { item ->
            item.phrases.split("|", ",", "،", ";").any { phrase ->
                val p = normalize(phrase)
                p.isNotBlank() && (normalized == p || normalized.contains(p))
            }
        }
        if (found == null) {
            toast("برای این عبارت دستوری تعریف نشده است")
        } else {
            sendPayload(found.payload)
            toast("فرمان ارسال شد: ${found.payload}")
        }
    }

    private fun showCommandEditor(existingIndex: Int?) {
        val existing = existingIndex?.let { commands.getOrNull(it) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val phraseInput = EditText(this).apply {
            hint = "عبارت‌ها را با ویرگول جدا کن؛ مثلاً چراغ روشن، لامپ را روشن کن"
            setText(existing?.phrases.orEmpty())
            textDirection = View.TEXT_DIRECTION_RTL
        }
        val payloadOptions = mutableListOf<String>()
        for (i in 1..8) {
            payloadOptions.add("خروجی $i روشن|OUT${i}_ON")
            payloadOptions.add("خروجی $i خاموش|OUT${i}_OFF")
        }
        payloadOptions.add("خاموش کردن همه|ALL_OFF")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            payloadOptions.map { it.substringBefore("|") })
        val selected = existing?.payload
        val selectedIndex = payloadOptions.indexOfFirst { it.endsWith("|$selected") }
        if (selectedIndex >= 0) spinner.setSelection(selectedIndex)
        box.addView(label("عبارت صوتی فارسی", 14f, Color.DKGRAY))
        box.addView(phraseInput)
        box.addView(label("فرمان مقصد", 14f, Color.DKGRAY))
        box.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "افزودن دستور" else "ویرایش دستور")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val phrases = phraseInput.text.toString().trim()
                if (phrases.isBlank()) {
                    toast("عبارت صوتی را وارد کن")
                    return@setPositiveButton
                }
                val payload = payloadOptions[spinner.selectedItemPosition].substringAfter("|")
                val command = VoiceCommand(phrases, payload)
                if (existingIndex == null) commands.add(command) else commands[existingIndex] = command
                saveCommands()
                renderCommands()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun renderCommands() {
        if (!::commandsContainer.isInitialized) return
        commandsContainer.removeAllViews()
        if (commands.isEmpty()) {
            commandsContainer.addView(label("هنوز دستوری اضافه نشده است.", 13f, muted))
            return
        }
        commands.forEachIndexed { index, command ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.rgb(35, 55, 80))
            }
            row.addView(label(command.phrases, 14f, pale))
            row.addView(label("فرمان: ${command.payload}", 12f, muted))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                text = "ویرایش"
                setOnClickListener { showCommandEditor(index) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            actions.addView(Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("این دستور حذف شود؟")
                        .setPositiveButton("حذف") { _, _ ->
                            commands.removeAt(index)
                            saveCommands()
                            renderCommands()
                        }
                        .setNegativeButton("انصراف", null)
                        .show()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(actions)
            commandsContainer.addView(row)
            commandsContainer.addView(space(6))
        }
    }

    private fun saveCommands() {
        val array = JSONArray()
        commands.forEach {
            array.put(JSONObject().put("phrases", it.phrases).put("payload", it.payload))
        }
        getSharedPreferences("smart_overlook", MODE_PRIVATE).edit()
            .putString("commands", array.toString()).apply()
    }

    private fun loadCommands() {
        val raw = getSharedPreferences("smart_overlook", MODE_PRIVATE).getString("commands", null)
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    commands.add(VoiceCommand(obj.optString("phrases"), obj.optString("payload")))
                }
            } catch (_: Exception) { commands.clear() }
        }
        if (commands.isEmpty()) {
            commands.add(VoiceCommand("چراغ روشن، چراغ را روشن کن", "OUT1_ON"))
            commands.add(VoiceCommand("چراغ خاموش، چراغ را خاموش کن", "OUT1_OFF"))
            commands.add(VoiceCommand("فن روشن، فن را روشن کن", "OUT2_ON"))
            commands.add(VoiceCommand("فن خاموش، فن را خاموش کن", "OUT2_OFF"))
            commands.add(VoiceCommand("پمپ روشن، پمپ را روشن کن", "OUT3_ON"))
            commands.add(VoiceCommand("پمپ خاموش، پمپ را خاموش کن", "OUT3_OFF"))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { bluetoothSocket?.close() } catch (_: Exception) {}
    }
}

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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val cyan = Color.rgb(75, 210, 255)
    private val green = Color.rgb(68, 220, 170)
    private val outputButtons = arrayOfNulls<Button>(8)

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
        try {
            window.statusBarColor = navy
            window.navigationBarColor = navy
            loadCommands()
            buildScreen()
            updateConnectionUi()
        } catch (error: Throwable) {
            android.util.Log.e("SmartOverlook", "Startup failure", error)
            showStartupError(error)
        }
    }

    private fun showStartupError(error: Throwable) {
        val details = android.util.Log.getStackTraceString(error)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
            setBackgroundColor(Color.rgb(16, 27, 45))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val heading = TextView(this).apply {
            text = "Smart Overlook - Startup diagnostic"
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        val message = TextView(this).apply {
            text = "برنامه هنگام شروع با خطا روبه‌رو شد. از این صفحه عکس بگیر و بفرست:\n\n" + details
            textSize = 12f
            setTextColor(Color.rgb(226, 238, 250))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this)
        scroll.addView(message)
        root.addView(heading)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(content)
        root.addView(scroll, ViewGroup.LayoutParams(-1, -1))

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val mark = TextView(this).apply {
            text = "S"
            textSize = 25f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedDrawable(intArrayOf(blue, cyan), dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(label("SMART OVERLOOK", 19f, pale).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.12f
        })
        brandText.addView(label("SMART HOME CONTROL", 10f, cyan).apply { letterSpacing = 0.16f })
        brandRow.addView(mark)
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        brandRow.addView(label("v1.0", 11f, muted, Gravity.CENTER).apply {
            background = roundedDrawable(intArrayOf(Color.rgb(31, 49, 73)), dp(20), dp(1), Color.rgb(53, 78, 108))
            setPadding(dp(12), dp(7), dp(12), dp(7))
        })
        content.addView(brandRow)
        content.addView(space(22))

        content.addView(label("کنترل هوشمند،", 27f, pale).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        content.addView(label("ساده و دقیق.", 27f, cyan).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        content.addView(label("مدیریت تجهیزات متصل به آردوینو از یک پنل واحد", 13f, muted))
        content.addView(space(20))

        val connectionCard = card()
        val connectionTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val connectionInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        connectionInfo.addView(label("اتصال دستگاه", 17f, pale).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        statusText = label("●  بلوتوث متصل نیست", 12f, muted)
        connectionInfo.addView(statusText)
        connectionTop.addView(connectionInfo, LinearLayout.LayoutParams(0, -2, 1f))
        connectionTop.addView(label("BT", 13f, cyan, Gravity.CENTER).apply {
            typeface = Typeface.DEFAULT_BOLD
            background = roundedDrawable(intArrayOf(Color.rgb(23, 53, 79)), dp(14), dp(1), Color.rgb(48, 107, 145))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        })
        connectionCard.addView(connectionTop)
        connectionCard.addView(space(12))
        connectButton = styledButton("انتخاب و اتصال به HC-05", true).apply {
            setOnClickListener { requestBluetoothAndConnect() }
        }
        connectionCard.addView(connectButton)
        content.addView(connectionCard)
        content.addView(space(14))

        val voiceCard = card()
        voiceCard.addView(label("فرمان صوتی فارسی", 17f, pale).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        voiceCard.addView(label("با گفتن عبارت‌های تعریف‌شده، تجهیزات را کنترل کن.", 12f, muted))
        recognizedText = label("منتظر فرمان صوتی شما هستم…", 13f, cyan).apply {
            background = roundedDrawable(intArrayOf(Color.rgb(19, 34, 54)), dp(12), dp(1), Color.rgb(44, 72, 102))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        voiceCard.addView(space(8))
        voiceCard.addView(recognizedText)
        voiceCard.addView(space(12))
        val mic = styledButton("🎙   برای صحبت لمس کن", true).apply {
            textSize = 16f
            setOnClickListener { startPersianRecognition() }
        }
        voiceCard.addView(mic)
        voiceCard.addView(label("برای تشخیص گفتار، اینترنت و سرویس گفتار فارسی گوشی لازم است.", 11f, muted))
        content.addView(voiceCard)
        content.addView(space(14))

        val outputCard = card()
        val outputHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        outputHeader.addView(label("خروجی‌های دستگاه", 17f, pale).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        outputHeader.addView(label("۸ کانال", 11f, cyan).apply {
            background = roundedDrawable(intArrayOf(Color.rgb(23, 53, 79)), dp(16), 0)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        outputCard.addView(outputHeader)
        outputCard.addView(label("برای تغییر وضعیت هر کانال، کارت آن را لمس کن.", 12f, muted))
        outputCard.addView(space(8))
        val grid = GridLayout(this).apply {
            columnCount = 2
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        for (i in 0 until 8) {
            val button = Button(this).apply {
                isAllCaps = false
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(5), dp(4), dp(5), dp(4))
                updateOutputButton(this, i)
                setOnClickListener {
                    val next = !outputStates[i]
                    sendPayload("OUT${i + 1}_${if (next) "ON" else "OFF"}")
                    outputStates[i] = next
                    updateOutputButton(this, i)
                }
            }
            outputButtons[i] = button
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(66)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(button, params)
        }
        outputCard.addView(grid)
        outputCard.addView(space(10))
        val allOff = styledButton("خاموش کردن همه خروجی‌ها", false).apply {
            setOnClickListener {
                sendPayload("ALL_OFF")
                for (i in outputStates.indices) {
                    outputStates[i] = false
                    outputButtons[i]?.let { updateOutputButton(it, i) }
                }
                toast("فرمان خاموشی همه خروجی‌ها ارسال شد")
            }
        }
        outputCard.addView(allOff)
        content.addView(outputCard)
        content.addView(space(14))

        val commandCard = card()
        commandCard.addView(label("شخصی‌سازی فرمان‌ها", 17f, pale).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        commandCard.addView(label("عبارت‌های دلخواه فارسی را به خروجی موردنظر متصل کن.", 12f, muted))
        commandsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        commandCard.addView(commandsContainer)
        commandCard.addView(space(8))
        val add = styledButton("+  افزودن دستور جدید", false).apply {
            setOnClickListener { showCommandEditor(null) }
        }
        commandCard.addView(add)
        content.addView(commandCard)

        content.addView(space(18))
        content.addView(label("SMART OVERLOOK  •  ARDUINO CONTROL", 10f, Color.rgb(100, 128, 157), Gravity.CENTER).apply {
            letterSpacing = 0.08f
        })

        setContentView(root)
        renderCommands()
    }

    private fun updateOutputButton(button: Button, index: Int) {
        val on = outputStates[index]
        button.text = "خروجی ${index + 1}\n${if (on) "● روشن" else "○ خاموش"}"
        button.setTextColor(if (on) Color.rgb(140, 255, 218) else pale)
        button.background = roundedDrawable(
            if (on) intArrayOf(Color.rgb(22, 88, 82), Color.rgb(24, 66, 76))
            else intArrayOf(Color.rgb(34, 52, 76), Color.rgb(27, 43, 65)),
            dp(13), dp(1), if (on) green else Color.rgb(57, 81, 111)
        )
    }

    private fun roundedDrawable(
        colors: IntArray,
        radius: Int,
        strokeWidth: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable = GradientDrawable(
        if (colors.size > 1) GradientDrawable.Orientation.TL_BR else GradientDrawable.Orientation.LEFT_RIGHT,
        colors
    ).apply {
        cornerRadius = radius.toFloat()
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    private fun styledButton(textValue: String, primary: Boolean): Button = Button(this).apply {
        text = textValue
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = if (primary)
            roundedDrawable(intArrayOf(Color.rgb(37, 126, 207), Color.rgb(45, 174, 224)), dp(13))
        else
            roundedDrawable(intArrayOf(Color.rgb(39, 60, 86), Color.rgb(32, 49, 72)), dp(13), dp(1), Color.rgb(65, 93, 126))
        minHeight = dp(50)
        stateListAnimator = null
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedDrawable(intArrayOf(Color.rgb(29, 46, 69), Color.rgb(24, 39, 60)), dp(18), dp(1), Color.rgb(48, 70, 99))
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        elevation = dp(3).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(2)
        }
    }

    private fun label(textValue: String, size: Float, color: Int, gravityValue: Int = Gravity.RIGHT): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            gravity = gravityValue
            setPadding(0, dp(4), 0, dp(4))
            textDirection = View.TEXT_DIRECTION_RTL
        }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rebuildScreenOutputs() {
        for (i in outputStates.indices) {
            outputStates[i] = false
            outputButtons[i]?.let { updateOutputButton(it, i) }
        }
    }

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

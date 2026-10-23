package online.seuprojeto.filtrofanta

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class CameraConfigActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentCamera = prefs.getString(KEY_CAMERA_TYPE, "emeet") ?: "emeet"

        val group = RadioGroup(this).apply {
            layoutParams = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )

            val emeetRadio = RadioButton(this@CameraConfigActivity).apply {
                id = 1
                text = "EMEET USB (OTG no USB-C — tablet ou celular)"
                isChecked = (currentCamera == "emeet")
            }
            addView(emeetRadio)

            val deviceRadio = RadioButton(this@CameraConfigActivity).apply {
                id = 2
                text = "Câmera do Dispositivo (Celular ou Tablet)"
                isChecked = (currentCamera == "device")
            }
            addView(deviceRadio)

            setOnCheckedChangeListener { _, checkedId ->
                val selected = if (checkedId == 1) "emeet" else "device"
                prefs.edit().putString(KEY_CAMERA_TYPE, selected).apply()
                setResult(RESULT_OK)
                finish()
            }
        }

        setContentView(group)
    }

    companion object {
        const val PREFS_NAME = "booth_prefs"
        const val KEY_CAMERA_TYPE = "camera_type"
    }
}

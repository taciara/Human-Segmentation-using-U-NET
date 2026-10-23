package online.seuprojeto.filtrofanta

import android.app.Application
import com.serenegiant.utils.UVCUtils

class FiltroFantaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UVCUtils.init(this)
    }
}

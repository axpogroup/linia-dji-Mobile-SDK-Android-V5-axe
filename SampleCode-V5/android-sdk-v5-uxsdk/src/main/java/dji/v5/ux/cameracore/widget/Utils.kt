package dji.v5.ux.cameracore.widget

import android.util.Log
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import io.reactivex.rxjava3.functions.Action
import io.reactivex.rxjava3.functions.Consumer

fun <T> setValue(key: DJIKeyInfo<T>, value: T, onSuccess: Action? = null, onError: Consumer<Throwable>? = null) {
    KeyManager.getInstance().setValue(
        KeyTools.createKey(key), value,
        object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                onSuccess?.run()
                Log.d("SetValue", "Successfully set $key to $value")
            }

            override fun onFailure(error: IDJIError) {
                Log.e("SetValue", error.toString())
                onError?.accept(Throwable(error.toString()))
            }
        })
}
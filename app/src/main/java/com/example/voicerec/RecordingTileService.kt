package com.example.voicerec

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class RecordingTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (RecorderState.isRecording.value) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        } else {
            // Il microfono si può avviare solo con un'activity visibile: la apriamo e poi torna indietro.
            val i = Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                legacyStart(i)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyStart(i: Intent) = startActivityAndCollapse(i)

    private fun refresh() {
        val rec = RecorderState.isRecording.value
        qsTile?.let {
            it.state = if (rec) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = if (rec) "Ferma registrazione" else "Registra"
            it.updateTile()
        }
    }
}

package ca.heeney.doppler

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** The copies of the library already on the phone (Syncthing puts them there), found through
 * Android's media index and matched to the hub's track ids by the end of their path. */
object LocalFiles {
    val permission: String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(context: Context) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Null when the media index couldn't be read (it can fail while the system is short of memory),
     * so a failed look isn't taken for "nothing here". */
    fun find(context: Context, ids: Collection<String>): Map<String, Uri>? {
        if (!allowed(context) || ids.isEmpty()) return emptyMap()
        // index every track id by its file name, then check the folders above it
        val byName = HashMap<String, MutableList<String>>()
        for (id in ids) byName.getOrPut(id.substringAfterLast("/")) { ArrayList() }.add(id)
        val out = HashMap<String, Uri>()
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME)
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return runCatching {
            val cursor = context.contentResolver.query(collection, columns, null, null, null) ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2) ?: continue
                    val candidates = byName[name] ?: continue
                    val path = (c.getString(1) ?: "").trimEnd('/') + "/" + name
                    val id = candidates.firstOrNull { path == it || path.endsWith("/$it") } ?: continue
                    if (id !in out) out[id] = ContentUris.withAppendedId(collection, c.getLong(0))
                }
            }
            out
        }.getOrNull()
    }
}

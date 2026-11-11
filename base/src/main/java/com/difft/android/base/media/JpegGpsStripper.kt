package com.difft.android.base.media

import androidx.annotation.WorkerThread
import androidx.exifinterface.media.ExifInterface
import java.io.File

object JpegGpsStripper {

    sealed interface StripResult {
        data object Stripped : StripResult
        data object NotJpeg : StripResult
        data class Failed(val reason: FailureReason) : StripResult
    }

    enum class FailureReason { IO, NOT_JPEG, VERIFY, OOM }

    
    @WorkerThread
    fun strip(file: File): StripResult = try {
        val isJpeg = file.inputStream().use { it.read() == 0xff && it.read() == 0xd8 && it.read() == 0xff }
        if (!isJpeg) {
            StripResult.NotJpeg
        } else {
            ExifInterface(file).apply {
                SENSITIVE_TAGS.forEach { setAttribute(it, null) }
                
                saveAttributes()
            }
            val verified = ExifInterface(file)
            if (verified.latLong != null || SENSITIVE_TAGS.any { verified.getAttribute(it) != null }) {
                StripResult.Failed(FailureReason.VERIFY)
            } else {
                StripResult.Stripped
            }
        }
    } catch (_: OutOfMemoryError) {
        StripResult.Failed(FailureReason.OOM)
    } catch (_: Exception) {
        StripResult.Failed(FailureReason.IO)
    }

    



    private val SENSITIVE_TAGS = listOf(
        "GPSInfoIFDPointer",
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_SUBJECT_LOCATION,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR
    )
}

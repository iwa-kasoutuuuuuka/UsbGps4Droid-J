package org.broeuschmeul.android.gps.usb.provider.util

import android.location.Location
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 取得した位置情報（Location）をGPX 1.1規格のXML形式で書き出すヘルパークラスです。
 */
class GpxLogger(private val writer: PrintWriter) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun writeHeader() {
        writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writer.println("<gpx version=\"1.1\" creator=\"UsbGps4Droid-J\"")
        writer.println("     xmlns=\"http://www.topografix.com/GPX/1/1\"")
        writer.println("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        writer.println("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">")
        writer.println("  <metadata>")
        writer.println("    <time>${dateFormat.format(Date())}</time>")
        writer.println("  </metadata>")
        writer.println("  <trk>")
        writer.println("    <name>USB GPS Track Log</name>")
        writer.println("    <trkseg>")
        writer.flush()
    }

    fun writeTrackPoint(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val ele = location.altitude
        val time = dateFormat.format(Date(location.time))
        
        writer.println("      <trkpt lat=\"$lat\" lon=\"$lon\">")
        writer.println("        <ele>$ele</ele>")
        writer.println("        <time>$time</time>")
        if (location.hasSpeed()) {
            writer.println("        <speed>${location.speed}</speed>")
        }
        if (location.hasBearing()) {
            writer.println("        <course>${location.bearing}</course>")
        }
        writer.println("      </trkpt>")
        writer.flush()
    }

    fun writeFooter() {
        writer.println("    </trkseg>")
        writer.println("  </trk>")
        writer.println("</gpx>")
        writer.flush()
    }
}
